package com.wledclimb.app.wall

import com.wledclimb.app.palette.HoldColor
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledclimb.app.network.WledConfigException
import com.wledclimb.app.grid.Wall
import com.wledclimb.app.network.WledIdentity
import com.wledclimb.app.network.WledIdentityException
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.storage.RouteHolds
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.StoredRoute
import com.wledclimb.app.storage.StoredWall
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WallViewModel"

/**
 * Connects to the WLED controller saved during setup: turns the whole wall
 * on/off, and asks the client for the wall's shape. Where that shape comes
 * from, and what WLED says to describe it, is the client's business now.
 */
class WallViewModel(
    private val client: WledClient,
    private val walls: WallRepository,
    private val routes: RouteRepository,
    private val controllerAddress: String
) : ViewModel() {

    private val _uiState = MutableStateFlow<WallUiState>(WallUiState.Connecting)
    val uiState: StateFlow<WallUiState> = _uiState.asStateFlow()

    /**
     * Routes saved for the connected wall, newest first.
     *
     * Follows the wall rather than being loaded once: switching controllers
     * swaps the list, and a wall that could not be stored has none. Collected
     * eagerly so the list is ready when the UI asks, since it is the landing
     * content rather than something opened on demand.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val savedRoutes: StateFlow<List<StoredRoute>> = _uiState
        .map { (it as? WallUiState.Connected)?.wallId }
        .distinctUntilChanged()
        .flatMapLatest { wallId ->
            if (wallId == null) flowOf(emptyList()) else routes.forWall(wallId)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Pending brightness, at most one waiting.
     *
     * A Slider reports every pixel of a drag, and firing a request per report
     * put dozens of them at an ESP32 in a second - which answered by dropping
     * connections ("unexpected end of stream"). Conflating means one request is
     * in flight at a time and newer values replace the waiting one rather than
     * queueing behind it, so the wall still tracks the finger but is asked at a
     * rate it can answer.
     */
    private val brightnessRequests = Channel<Int>(Channel.CONFLATED)

    /**
     * The most recent brightness asked for, which is not necessarily the one
     * being confirmed: conflation means a reply can describe a value the user
     * has already moved past.
     */
    private var requestedBrightness: Int? = null

    /**
     * The open route's holds as they are saved, against which "modified" is
     * judged. Empty for a route nobody has saved yet, which is the right
     * baseline: a blank wall is unmodified, and the first hold makes it a
     * draft worth keeping.
     *
     * Not persisted - it is recovered from the open route on connect.
     */
    private var savedHolds: String = ""

    init {
        refresh()
        viewModelScope.launch {
            for (target in brightnessRequests) {
                applyBrightness(target)
            }
        }
    }

    private suspend fun applyBrightness(brightness: Int) {
        val current = _uiState.value as? WallUiState.Connected ?: return
        try {
            val status = client.setBrightness(brightness = brightness, on = current.on)
            val latest = _uiState.value as? WallUiState.Connected ?: return
            // Power is always worth taking from the reply. Brightness only when
            // nothing newer has been asked for, or a slow reply would drag the
            // value back to where the finger has already left.
            val superseded = requestedBrightness != brightness
            _uiState.value = latest.copy(
                brightness = if (superseded) latest.brightness else status.brightness,
                on = status.on
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately not an error state. A dropped brightness request is
            // not evidence the wall has gone: it is one request among many
            // during a drag, and tearing down the screen over it loses the
            // grid, the route and the user's place. The displayed value stands
            // until the next successful set or refresh corrects it, and a wall
            // that really is unreachable will say so on the next toggle or tap.
            Log.e(TAG, "setBrightness($brightness) failed", e)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = WallUiState.Connecting
            // What the wall was left showing, restored once the state is in
            // place because the restore reads it.
            var restore: StoredWall? = null
            _uiState.value = try {
                // The three reads don't depend on each other, and run against a
                // small controller over Wi-Fi - in sequence their connect timeouts
                // stack up, so a dead controller took three timeouts to report.
                coroutineScope {
                    val status = async { client.getStatus() }
                    val identity = async { client.getIdentity() }
                    val wall = async { client.getWall() }.await()
                    val stored = storedWall(identity.await(), wall)
                    restore = stored
                    WallUiState.Connected(
                        on = status.await().on,
                        brightness = status.await().brightness,
                        name = identity.await().name,
                        wall = wall,
                        wallId = stored?.id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "refresh() failed", e)
                WallUiState.Error(problemFor(e))
            }

            restore?.let { restoreWorkingState(it) }
        }
    }

    /**
     * The row this wall is stored as, or null if it could not be stored.
     *
     * Deliberately not allowed to fail the connect. The database is not needed
     * to light a hold, so a storage problem costs saving routes and nothing
     * else - turning it into a connection error would take away the grid over
     * a failure that has nothing to do with the controller.
     */
    private suspend fun storedWall(identity: WledIdentity, wall: Wall): StoredWall? =
        try {
            walls.findOrCreate(
                controllerMac = identity.mac,
                name = identity.name,
                controllerAddress = controllerAddress,
                wall = wall
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "storing the wall failed; routes cannot be saved", e)
            null
        }

    /**
     * Puts back whatever the wall was left showing, saved or not.
     *
     * A draft wins over the route it came from: unsaved work is the more
     * recent truth, and discarding it because the app closed would be exactly
     * the loss the draft exists to prevent. Nothing is saved on the way
     * through - the route on disk stays as it was, and the wall comes back
     * looking like the edit was made a moment ago.
     *
     * Pushed, not merely displayed. Everywhere else in here what the app shows
     * is what it last sent, and a screen showing holds it had not pushed would
     * be the one place that is not true. The app cannot read the wall back to
     * check either - WLED answers /json/live with 501 - so asserting the state
     * it knows about beats displaying a guess.
     */
    private suspend fun restoreWorkingState(stored: StoredWall) {
        val current = _uiState.value as? WallUiState.Connected ?: return
        val route = stored.lastSelectedRouteId?.let { routes.byId(it) }

        // Empty when the route has been deleted, here or from another device.
        // The draft is still worth restoring: it is what someone was building.
        savedHolds = route?.holds.orEmpty()
        val working = stored.draftHolds ?: savedHolds
        if (working.isEmpty() && route == null) return

        _uiState.value = current.copy(selectedRouteId = route?.id)
        val latest = _uiState.value as? WallUiState.Connected ?: return
        showAndPush(latest, RouteHolds.parseSegments(working, latest.wall), "restore")
    }

    /** Picks the colour the next tapped hold will be painted in. */
    fun selectColor(color: HoldColor) {
        val current = _uiState.value as? WallUiState.Connected ?: return
        _uiState.value = current.copy(selectedColor = color)
    }

    /**
     * Paints, repaints or clears one hold, then pushes the whole route.
     *
     * Tapping a hold that's already the selected colour turns it off, so the
     * same gesture both paints and erases and there's no separate eraser mode
     * to explain. Tapping one showing a different colour repaints it.
     */
    fun toggleHold(segmentIndex: Int) {
        val current = _uiState.value as? WallUiState.Connected ?: return

        val updated = current.litHolds.toMutableMap()
        if (updated[segmentIndex] == current.selectedColor) {
            updated.remove(segmentIndex)
        } else {
            updated[segmentIndex] = current.selectedColor
        }
        showAndPush(current, updated, "toggleHold($segmentIndex)")
    }

    /**
     * Turns every hold off, to start a fresh route.
     *
     * Without this, clearing a route means tapping each lit hold in turn - one
     * request per hold, and a lot of tapping for anything but a short route.
     */
    fun clearWall() {
        val current = _uiState.value as? WallUiState.Connected ?: return
        if (current.litHolds.isEmpty()) return

        showAndPush(current, emptyMap(), "clearWall()")
    }

    /**
     * Saves what is on the wall, overwriting [routeId] or creating a route.
     *
     * Does nothing without a stored wall to hang it off. That is the case
     * where the database could not be opened, and it is reported by the save
     * action being unavailable rather than by failing here.
     */
    fun saveRoute(name: String, routeId: Long? = null) {
        val current = _uiState.value as? WallUiState.Connected ?: return
        val wallId = current.wallId ?: return

        viewModelScope.launch {
            try {
                val saved = routes.save(
                    wallId = wallId,
                    name = name,
                    holds = current.litHolds,
                    wall = current.wall,
                    routeId = routeId
                )
                // What is on the wall is now what is on disk, so it stops
                // counting as unsaved work: the flag in state has to be
                // cleared as well as the baseline it is judged against.
                savedHolds = RouteHolds.serializeSegments(current.litHolds, current.wall)
                (_uiState.value as? WallUiState.Connected)?.let {
                    _uiState.value = it.copy(modified = false)
                }
                select(saved)
                clearDraft()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "saveRoute($name) failed", e)
            }
        }
    }

    /**
     * Shows a saved route and pushes it to the wall.
     *
     * Holds the wall no longer has are dropped on the way through - see
     * [RouteRepository.load]. The route keeps them, so they return if the wall
     * does.
     */
    fun loadRoute(routeId: Long) {
        val current = _uiState.value as? WallUiState.Connected ?: return

        viewModelScope.launch {
            val holds = try {
                routes.load(routeId, current.wall)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadRoute($routeId) failed", e)
                null
            } ?: return@launch

            // Set before the push: showAndPush judges "modified" against this,
            // so a freshly opened route reads as unmodified rather than as an
            // edit of whatever was on the wall a moment ago.
            savedHolds = routes.byId(routeId)?.holds.orEmpty()
            select(routeId)
            val latest = _uiState.value as? WallUiState.Connected ?: return@launch
            showAndPush(latest, holds, "loadRoute($routeId)")
        }
    }

    /**
     * Clears the wall and starts a route belonging to nothing.
     *
     * Not the same as [clearWall], which empties the route that is open and
     * leaves it open - that is an edit, and it counts as one. This closes the
     * route first, so what follows is new work rather than the old route
     * emptied.
     */
    fun newRoute() {
        val current = _uiState.value as? WallUiState.Connected ?: return

        viewModelScope.launch {
            savedHolds = ""
            select(null)
            val latest = _uiState.value as? WallUiState.Connected ?: return@launch
            showAndPush(latest, emptyMap(), "newRoute()")
        }
    }

    /**
     * Throws away unsaved edits and goes back to the route as saved.
     *
     * With nothing open there is no saved state to return to, so the wall
     * clears - a draft belonging to no route reverts to no route. That is the
     * same thing [newRoute] does, and deliberately the same code: "undo my
     * edits" and "start again" are the same action when there is nothing
     * behind the edits.
     */
    fun revertRoute() {
        val current = _uiState.value as? WallUiState.Connected ?: return
        if (!current.modified) return

        val open = current.selectedRouteId
        if (open == null) newRoute() else loadRoute(open)
    }

    private suspend fun clearDraft() {
        val wallId = (_uiState.value as? WallUiState.Connected)?.wallId ?: return
        try {
            walls.saveDraft(wallId, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "clearing the draft failed", e)
        }
    }

    fun renameRoute(routeId: Long, name: String) {
        viewModelScope.launch {
            try {
                routes.rename(routeId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "renameRoute($routeId) failed", e)
            }
        }
    }

    /**
     * Deletes a route, clearing the wall if that route was the one open.
     *
     * Leaving the holds up was the first behaviour here, on the reasoning that
     * whoever deleted a route could still see what they deleted. Drafts make
     * that wrong: holds belonging to a route that no longer exists are unsaved
     * work by definition, so the wall would sit there modified, and switching
     * away would offer to save a route that had just been deliberately thrown
     * away. Deleting what you are looking at should take it off the wall.
     *
     * Deleting some other route touches nothing. It is not what is on the
     * wall, and removing it from a list is not a reason to change the wall.
     */
    fun deleteRoute(routeId: Long) {
        viewModelScope.launch {
            try {
                routes.delete(routeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "deleteRoute($routeId) failed", e)
                return@launch
            }

            val current = _uiState.value as? WallUiState.Connected ?: return@launch
            if (current.selectedRouteId != routeId) return@launch

            // Baseline first, so the clear that follows reads as unmodified
            // rather than as an edit of the route that has just gone.
            savedHolds = ""
            select(null)
            val latest = _uiState.value as? WallUiState.Connected ?: return@launch
            showAndPush(latest, emptyMap(), "deleteRoute($routeId)")
        }
    }

    /**
     * Records the route in state and on the wall row.
     *
     * Persisted so the app can come back to it next launch; failing to write
     * it costs the reselection and nothing else, so it is logged rather than
     * surfaced.
     */
    private suspend fun select(routeId: Long?) {
        val current = _uiState.value as? WallUiState.Connected ?: return
        _uiState.value = current.copy(selectedRouteId = routeId)
        val wallId = current.wallId ?: return
        try {
            walls.selectRoute(wallId, routeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "recording the selected route failed", e)
        }
    }

    /**
     * Shows [holds] straight away and pushes them to the wall in the background.
     *
     * The grid updates before the request completes: on a local network the
     * round trip is short, but waiting for it would make every tap feel
     * sticky. A failed push falls back to the error state, same as a failed
     * on/off toggle, rather than silently leaving the app and the wall
     * showing different things.
     */
    private fun showAndPush(
        current: WallUiState.Connected,
        holds: Map<Int, HoldColor>,
        description: String
    ) {
        val asStored = RouteHolds.serializeSegments(holds, current.wall)
        val modified = asStored != savedHolds
        _uiState.value = current.copy(litHolds = holds, modified = modified)

        // Every hold change passes through here, so this is the one place the
        // draft has to be written. Unsaved work then survives the app being
        // closed or killed without anyone having to remember to save.
        current.wallId?.let { wallId ->
            viewModelScope.launch {
                try {
                    walls.saveDraft(wallId, asStored.takeIf { modified })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "recording the draft failed", e)
                }
            }
        }

        viewModelScope.launch {
            try {
                client.setHoldColors(pixelCount = current.wall.segmentSize, lit = holds.toHex())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$description failed", e)
                _uiState.value = WallUiState.Error(problemFor(e))
            }
        }
    }

    /**
     * Sets master brightness.
     *
     * The wall's power state is passed along deliberately: WLED derives power
     * from brightness when the field is absent, so a bare brightness change
     * would switch a sleeping wall back on. The client also clamps away from
     * zero, because brightness rising from zero is what makes WLED unfreeze
     * its segments and drop the route.
     *
     * Unlike a hold tap this does not need the route re-pushing - the route
     * survives a brightness change, so long as brightness never reaches zero.
     */
    fun setBrightness(brightness: Int) {
        val current = _uiState.value as? WallUiState.Connected ?: return
        // A drag reports once per frame, and consecutive frames routinely land
        // on the same integer once the slider's float is truncated - a 2s drag
        // at 120Hz reports 250 times across at most 248 distinct values. The
        // write below would be suppressed anyway, since an unchanged copy
        // compares equal and StateFlow drops it, but establishing that costs a
        // structural comparison of the whole grid and route every frame. The
        // conflated send would collapse too. Neither is worth reaching.
        if (brightness == current.brightness) return
        // Moves with the finger. The request that follows is conflated, so the
        // slider stays smooth whatever the controller is keeping up with.
        _uiState.value = current.copy(brightness = brightness)
        requestedBrightness = brightness
        brightnessRequests.trySend(brightness)
    }

    fun toggleWall() {
        val current = _uiState.value as? WallUiState.Connected ?: return
        if (current.busy) return
        _uiState.value = current.copy(busy = true)

        viewModelScope.launch {
            _uiState.value = try {
                val status = client.setOn(on = !current.on)
                // WLED unfreezes every segment when it's switched on (see the
                // "unfreeze all segments when turning on" branch in json.cpp),
                // which drops the per-pixel route from the wall while the app
                // still shows it. Push the route again so the two agree.
                if (status.on && current.litHolds.isNotEmpty()) {
                    client.setHoldColors(pixelCount = current.wall.segmentSize, lit = current.litHolds.toHex())
                }
                // copy() rather than a fresh Connected, so the route stays put.
                current.copy(on = status.on, brightness = status.brightness, busy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "toggleWall() failed", e)
                WallUiState.Error(problemFor(e))
            }
        }
    }
}

/**
 * A controller that answered but isn't set up as a wall needs a different fix
 * from one that couldn't be reached at all.
 */
/** The wire format WLED wants, from the colours the UI works in. */
private fun Map<Int, HoldColor>.toHex(): Map<Int, String> = mapValues { it.value.hex }

private fun problemFor(e: Exception): WallProblem = when (e) {
    is WledConfigException -> WallProblem.NotAWledMatrix
    is WledIdentityException -> WallProblem.Unidentifiable
    else -> WallProblem.Unreachable
}
