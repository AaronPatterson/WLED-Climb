package com.wledclimb.app.gaps

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledclimb.app.grid.GapCell
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.network.WledConfigException
import com.wledclimb.app.network.WledLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GapEditorViewModel"

/**
 * How often a restarting controller is asked whether it is back. An ESP32
 * takes several seconds to boot and rejoin the Wi-Fi, so asking faster only
 * piles failed connections onto the network.
 */
private const val RESTART_POLL_MS = 2_000L

/**
 * How long to wait for it before giving up and going back to the wall anyway.
 * The layout is saved by then whatever happens; a controller that is slow to
 * come back shows as out of reach on the wall, with the usual way to try again.
 */
private const val RESTART_TIMEOUT_MS = 45_000L

/**
 * Edits the controller's gap file: which grid positions have a hold, which
 * have an LED with nothing in front of it, and which have no LED at all.
 *
 * Works like building a route. A brush is picked the way a colour is, and
 * tapping a position paints it; tapping one already painted with the brush
 * turns it back to an unused LED, the way tapping a hold in its own colour
 * turns it off. Nothing reaches the controller until saved.
 *
 * Needs the controller throughout. The gap file is read from it on opening
 * rather than rebuilt from the stored wall, which cannot tell the two kinds
 * of gap apart, and saving writes to it - so there is nothing useful to do
 * with no controller in reach, and the way in is only offered while online.
 */
class GapEditorViewModel(private val client: WledClient) : ViewModel() {

    private val _uiState = MutableStateFlow<GapEditorUiState>(GapEditorUiState.Loading)
    val uiState: StateFlow<GapEditorUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Reads the gap file, on opening and again after a failed read. */
    fun load() {
        if (_uiState.value is GapEditorUiState.Editing) return
        _uiState.value = GapEditorUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                val layout = client.getGapLayout()
                GapEditorUiState.Editing(original = layout, layout = layout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "reading the gap file failed", e)
                GapEditorUiState.LoadFailed(problemFor(e))
            }
        }
    }

    /** Picks what the next tapped position will be marked as. */
    fun selectBrush(brush: GapCell) {
        val current = _uiState.value as? GapEditorUiState.Editing ?: return
        _uiState.value = current.copy(brush = brush)
    }

    /**
     * Marks one position with the brush, or turns it back to an unused LED if
     * it is already marked with it.
     *
     * An unused LED is what "off" is here because it is the safe one: it
     * claims nothing about the wiring. Clearing to "no LED" would quietly
     * tell WLED a light that is there is not, and shift every LED after it.
     *
     * Positions no panel covers are not the gap file's to describe, and taps
     * on them do nothing.
     */
    fun paint(x: Int, y: Int) {
        val current = _uiState.value as? GapEditorUiState.Editing ?: return
        if (current.saving) return
        val existing = current.layout.cellAt(x, y) ?: return
        val painted = if (existing == current.brush) GapCell.Unused else current.brush
        if (painted == existing) return
        _uiState.value = current.copy(
            layout = current.layout.with(x, y, painted),
            problem = null
        )
    }

    /** Throws away every edit, back to what the controller has. */
    fun reset() {
        val current = _uiState.value as? GapEditorUiState.Editing ?: return
        if (current.saving) return
        _uiState.value = current.copy(layout = current.original, problem = null)
    }

    /**
     * Writes the gap file, then restarts the controller so it uses it.
     *
     * The restart is not optional. WLED reads the gap file only when it sets
     * up the matrix, which is at boot, and lights holds by grid position
     * through the mapping it built then. Without it the app would show the new
     * layout while the wall went on drawing the old one, putting holds on the
     * wrong LEDs.
     *
     * A failed upload changes nothing and leaves the edits on screen to try
     * again. A failed restart leaves them there too, still counted as unsaved,
     * so that saving again - the same file, and another restart - is the way
     * to finish.
     */
    fun save() {
        val current = _uiState.value as? GapEditorUiState.Editing ?: return
        if (current.saving || !current.modified) return
        _uiState.value = current.copy(saving = true, problem = null)

        viewModelScope.launch {
            try {
                client.saveGapLayout(current.layout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "uploading the gap file failed", e)
                failSave(problemFor(e))
                return@launch
            }

            try {
                client.restart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "restarting after saving the gap file failed", e)
                failSave(GapEditorProblem.NotRestarted)
                return@launch
            }

            _uiState.value = GapEditorUiState.Restarting
            awaitRestart()
            _uiState.value = GapEditorUiState.Saved
        }
    }

    private fun failSave(problem: GapEditorProblem) {
        val latest = _uiState.value as? GapEditorUiState.Editing ?: return
        _uiState.value = latest.copy(saving = false, problem = problem)
    }

    /**
     * Waits until the controller answers again, or for [RESTART_TIMEOUT_MS].
     *
     * Asked after a pause rather than straight away: WLED answers the restart
     * request and only then goes down, so an immediate question could be
     * answered by the controller that is about to disappear.
     */
    private suspend fun awaitRestart() {
        withTimeoutOrNull(RESTART_TIMEOUT_MS) {
            while (true) {
                delay(RESTART_POLL_MS)
                val back = try {
                    client.getStatus()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (back) break
            }
        }
    }
}

private fun problemFor(e: Exception): GapEditorProblem = when (e) {
    is WledLockedException -> GapEditorProblem.Locked
    is WledConfigException -> GapEditorProblem.NotAWledMatrix
    else -> GapEditorProblem.Unreachable
}
