package com.wledclimb.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledclimb.app.settings.WledSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen the app should show. */
sealed interface RootUiState {
    data object Loading : RootUiState

    /**
     * The two states that have a wall on screen, and so are somewhere setup
     * can be left to get back to.
     */
    sealed interface ShowingWall : RootUiState

    /**
     * [currentUrl] pre-fills the address field. [returnTo] is where leaving
     * without saving goes, and is null only on a first run, where there is no
     * wall behind this screen to go back to.
     *
     * Two fields rather than one, because they stopped being the same
     * question once the practice wall existed. A saved address can exist while
     * the practice wall is what is on screen - so there is something to
     * pre-fill and somewhere to go back to, and neither implies the other.
     * While one field answered both, leaving the practice wall for setup
     * offered an empty box and no way out.
     */
    data class NeedsSetup(
        val currentUrl: String? = null,
        val returnTo: ShowingWall? = null
    ) : RootUiState

    data class Ready(val wledBaseUrl: String) : RootUiState, ShowingWall

    /**
     * The practice wall: the wall screen with no controller behind it.
     *
     * Its own state rather than a [Ready] with no address, because every other
     * thing that reads an address would then have to ask whether this one is
     * real.
     */
    data object Demo : RootUiState, ShowingWall
}

/**
 * Decides whether to show the setup screen or wall control, based on whether
 * a WLED controller address has already been saved.
 */
class RootViewModel(private val settings: WledSettings) : ViewModel() {

    private val _uiState = MutableStateFlow<RootUiState>(RootUiState.Loading)
    val uiState: StateFlow<RootUiState> = _uiState.asStateFlow()

    /**
     * The saved controller address, kept here so leaving a wall for setup can
     * pre-fill the field without waiting on a read. Setup completing is the
     * only thing that writes it, so this cannot drift from what is stored.
     */
    private var savedUrl: String? = null

    init {
        viewModelScope.launch {
            savedUrl = settings.wledIp.first()
            _uiState.value = savedUrl?.let { RootUiState.Ready(it) } ?: RootUiState.NeedsSetup()
        }
    }

    fun onSetupComplete(wledBaseUrl: String) {
        savedUrl = wledBaseUrl
        _uiState.value = RootUiState.Ready(wledBaseUrl)
    }

    /** Shows the practice wall, without saving anything as the controller. */
    fun onUseDemoWall() {
        _uiState.value = RootUiState.Demo
    }

    /** Called from the wall screen to go back and point the app at a different controller. */
    fun onChangeController() {
        val current = _uiState.value as? RootUiState.ShowingWall ?: return
        _uiState.value = RootUiState.NeedsSetup(
            // The wall on screen if it has an address, and otherwise whatever
            // was last saved. Coming from the practice wall that is the real
            // controller, which is the one someone on their way back to it
            // would have to type out again from memory.
            currentUrl = (current as? RootUiState.Ready)?.wledBaseUrl ?: savedUrl,
            returnTo = current
        )
    }

    /**
     * Leaves setup without changing anything, returning to the wall that was
     * already working.
     *
     * Setup used to be a one-way door: it is reachable from a menu on the wall
     * screen, and the only button on it saves. A six-year-old who opens it
     * finds an address in a text box and no way back - and can edit the
     * address before working that out. The app is not broken at that point,
     * but there is no way for them to discover that.
     *
     * Does nothing on a first run, where there is no working address behind
     * the screen and leaving it would show a wall the app cannot reach.
     */
    fun onSetupCancelled() {
        val current = _uiState.value as? RootUiState.NeedsSetup ?: return
        _uiState.value = current.returnTo ?: return
    }
}
