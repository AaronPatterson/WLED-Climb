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
import com.wledclimb.app.grid.fingerprint
import com.wledclimb.app.settings.WledSettings
import com.wledclimb.app.storage.HoldGrid
import com.wledclimb.app.storage.RouteBackup
import com.wledclimb.app.storage.RouteBackupException
import com.wledclimb.app.storage.RouteHolds
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.StoredRoute
import com.wledclimb.app.storage.StoredWall
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "WallViewModel"

/**
 * The wall screen: opens the wall this device last reached from storage, then
 * connects to the WLED controller saved during setup to turn the wall on and
 * off and put routes on it. Where the wall's shape comes from, and what WLED
 * says to describe it, is the client's business.
 *
 * Everything about routes works with no controller in reach. What the
 * controller is doing is [WallUiState.Ready.controller], beside the route
 * rather than instead of it.
 */
class WallViewModel(
    private val client: WledClient,
    private val walls: WallRepository,
    private val routes: RouteRepository,
    private val settings: WledSettings,
    private val controllerAddress: String,
    /**
     * Where reading and writing a backup file happens. Injected so tests can
     * run it on their own dispatcher and assert without waiting on real disk.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow<WallUiState>(WallUiState.Loading)
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
        .map { (it as? WallUiState.Ready)?.wallId }
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
    private val _backupResult = MutableStateFlow<RouteBackupOutcome?>(null)

    /**
     * The last export or import, until the screen has shown it. Null the rest
     * of the time - this is a message waiting to be read, not a status.
     */
    val backupResult: StateFlow<RouteBackupOutcome?> = _backupResult.asStateFlow()

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
        start()
        viewModelScope.launch {
            for (target in brightnessRequests) {
                applyBrightness(target)
            }
        }
    }

    private suspend fun applyBrightness(brightness: Int) {
        val current = _uiState.value as? WallUiState.Ready ?: return
        val online = current.controller as? ControllerState.Online ?: return
        try {
            val status = client.setBrightness(brightness = brightness, on = online.on)
            val latest = _uiState.value as? WallUiState.Ready ?: return
            val latestOnline = latest.controller as? ControllerState.Online ?: return
            // Power is always worth taking from the reply. Brightness only when
            // nothing newer has been asked for, or a slow reply would drag the
            // value back to where the finger has already left.
            val superseded = requestedBrightness != brightness
            _uiState.value = latest.copy(
                controller = latestOnline.copy(
                    brightness = if (superseded) latestOnline.brightness else status.brightness,
                    on = status.on
                )
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

    /**
     * Launch: the stored wall first, then the controller - unless this device
     * was left paused, in which case the controller is not asked at all.
     *
     * The stored wall comes first so that a controller out of reach costs
     * nothing but the controller: the grid, the routes and whatever was being
     * built are all on this device.
     *
     * Nothing is sent to the wall on the way through. It may be showing
     * someone else's route by now, and opening the app is not a request to
     * replace it.
     */
    private fun start() {
        viewModelScope.launch {
            openStoredWall()
            val current = _uiState.value as? WallUiState.Ready
            if (current != null && pausedBefore()) {
                _uiState.value = current.copy(controller = ControllerState.Paused)
                return@launch
            }
            connect(sendOnArrival = false)
        }
    }

    /** Whether the device was paused when it was last used. */
    private suspend fun pausedBefore(): Boolean =
        try {
            settings.paused.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Connecting is how the app behaved before pausing existed, and
            // launching still sends nothing.
            Log.e(TAG, "reading the paused setting failed", e)
            false
        }

    /**
     * Asks the controller again: the power button while the wall is out of
     * reach or paused, and the retry when there is no wall to show at all.
     * The only way the app asks again - see [ControllerState.Offline].
     *
     * Unlike launching, what is on screen is sent once the controller
     * answers. Someone has deliberately come back to the wall, and coming
     * back means the wall shows what they see.
     */
    fun reconnect() {
        when ((_uiState.value as? WallUiState.Ready)?.controller) {
            // A second tap while the first is still being answered would only
            // stack another round of timeouts behind it.
            ControllerState.Connecting -> return
            // Nothing to come back from. The button switches power then.
            is ControllerState.Online -> return
            else -> Unit
        }
        // Straight away rather than once the pause is cleared below: that
        // write suspends, and a second tap landing in between would find the
        // wall still paused and ask twice.
        (_uiState.value as? WallUiState.Ready)?.let {
            _uiState.value = it.copy(controller = ControllerState.Connecting)
        }
        viewModelScope.launch {
            savePaused(false)
            if (_uiState.value !is WallUiState.Ready) openStoredWall()
            connect(sendOnArrival = true)
        }
    }

    /**
     * Leaves the controller alone until [reconnect], with it in reach or not,
     * so a route can be built while someone climbs the one on the wall.
     *
     * Not while connecting: the answer would arrive after the pause and
     * undo it.
     */
    fun pause() {
        val current = _uiState.value as? WallUiState.Ready ?: return
        when (current.controller) {
            is ControllerState.Online, is ControllerState.Offline -> Unit
            ControllerState.Connecting, ControllerState.Paused -> return
        }
        _uiState.value = current.copy(controller = ControllerState.Paused)
        viewModelScope.launch { savePaused(true) }
    }

    /**
     * Failing to persist costs only what happens after a relaunch, so it is
     * logged rather than surfaced.
     */
    private suspend fun savePaused(paused: Boolean) {
        try {
            settings.savePaused(paused)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "saving the paused setting failed", e)
        }
    }

    /**
     * Puts the wall this device last reached on screen, as it was left, with
     * the controller still to be asked.
     *
     * Leaves [WallUiState.Loading] in place when there is none - a first
     * run - so the controller is the only way to a wall, as it always was.
     *
     * Opened whatever address is saved now. A new address may lead to a
     * different controller, and when it answers [arrive] switches to its
     * wall; until then the last wall is the best there is to show.
     */
    private suspend fun openStoredWall() {
        _uiState.value = WallUiState.Loading
        val stored = try {
            settings.lastWallId.first()?.let { walls.byId(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "opening the stored wall failed", e)
            null
        } ?: return
        // A grid that does not match its dimensions is not a wall to draw.
        // The controller will supply a good one if it can be reached.
        val wall = HoldGrid.parse(stored.holdGrid, stored.width, stored.height) ?: return

        savedHolds = ""
        _uiState.value = WallUiState.Ready(
            name = stored.name,
            wall = wall,
            controller = ControllerState.Connecting,
            wallId = stored.id
        )
        restoreWorkingState(stored)
    }

    /** What the controller said about itself, gathered before any of it is used. */
    private class Arrival(
        val stored: StoredWall?,
        val name: String,
        val wall: Wall,
        val online: ControllerState.Online
    )

    private suspend fun connect(sendOnArrival: Boolean) {
        (_uiState.value as? WallUiState.Ready)?.let {
            _uiState.value = it.copy(controller = ControllerState.Connecting)
        }
        val arrival = try {
            // The three reads don't depend on each other, and run against a
            // small controller over Wi-Fi - in sequence their connect timeouts
            // stack up, so a dead controller took three timeouts to report.
            coroutineScope {
                val status = async { client.getStatus() }
                val identity = async { client.getIdentity() }
                val wall = async { client.getWall() }.await()
                Arrival(
                    stored = storedWall(identity.await(), wall),
                    name = identity.await().name,
                    wall = wall,
                    online = ControllerState.Online(
                        on = status.await().on,
                        brightness = status.await().brightness
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "connecting failed", e)
            val problem = problemFor(e)
            // With a wall on screen this is the controller being out of reach
            // and nothing more. Without one there is nothing to show.
            _uiState.value = when (val current = _uiState.value) {
                is WallUiState.Ready -> current.copy(controller = ControllerState.Offline(problem))
                else -> WallUiState.Error(problem)
            }
            return
        }
        arrive(arrival, sendOnArrival)
    }

    /**
     * Brings what the controller said into state.
     *
     * When it is the wall already on screen, the work on screen stays: it is
     * newer than anything stored, having been made since. Only the shape is
     * taken, because the controller is the authority on it - and the holds
     * are carried across it by position, since a changed width moves every
     * segment index. Holds the wall no longer has drop off the screen; the
     * draft on disk keeps them until the next edit. With [send], the work is
     * then put on the wall - see [reconnect].
     *
     * Any other wall - none on screen, or the controller at this address
     * turning out to be a different one - is opened as it was left, the same
     * as at launch.
     */
    private suspend fun arrive(arrival: Arrival, send: Boolean) {
        val stored = arrival.stored
        val current = _uiState.value as? WallUiState.Ready
        if (current != null && stored != null && current.wallId == stored.id) {
            val holds = if (current.wall == arrival.wall) {
                current.litHolds
            } else {
                RouteHolds.parseSegments(
                    RouteHolds.serializeSegments(current.litHolds, current.wall),
                    arrival.wall
                )
            }
            _uiState.value = current.copy(
                name = arrival.name,
                wall = arrival.wall,
                litHolds = holds,
                controller = arrival.online
            )
            rememberWall(stored)
            // Nothing on screen is not a route. Sending it would clear the
            // wall of whatever someone is climbing, for no route at all.
            if (send && holds.isNotEmpty()) push(arrival.wall, holds, "reconnect()")
            return
        }

        savedHolds = ""
        _uiState.value = WallUiState.Ready(
            name = arrival.name,
            wall = arrival.wall,
            controller = arrival.online,
            wallId = stored?.id
        )
        stored?.let {
            rememberWall(it)
            restoreWorkingState(it)
        }
    }

    /**
     * Records which wall the controller turned out to be, so it can be opened
     * the next time there is no controller to ask.
     *
     * Failing to record it costs that and nothing else - the wall is here and
     * working now - so it is logged rather than surfaced.
     */
    private suspend fun rememberWall(stored: StoredWall) {
        try {
            settings.saveLastWallId(stored.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "remembering the wall failed; it cannot be opened offline", e)
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
     * through - the route on disk stays as it was, and the screen comes back
     * looking like the edit was made a moment ago.
     *
     * Shown, not pushed. The wall may be showing someone else's route by now,
     * and opening the app is not a request to replace it; the app cannot read
     * the wall back to find out, since WLED answers /json/live with 501. The
     * next change - a tapped hold, a route opened - puts it on the wall.
     */
    private suspend fun restoreWorkingState(stored: StoredWall) {
        val current = _uiState.value as? WallUiState.Ready ?: return
        val route = stored.lastSelectedRouteId?.let { routes.byId(it) }

        // Empty when the route has been deleted, here or from another device.
        // The draft is still worth restoring: it is what someone was building.
        savedHolds = route?.holds.orEmpty()
        val working = stored.draftHolds ?: savedHolds
        if (working.isEmpty() && route == null) return

        _uiState.value = current.copy(selectedRouteId = route?.id)
        val latest = _uiState.value as? WallUiState.Ready ?: return
        show(latest, RouteHolds.parseSegments(working, latest.wall))
    }

    /** Picks the colour the next tapped hold will be painted in. */
    fun selectColor(color: HoldColor) {
        val current = _uiState.value as? WallUiState.Ready ?: return
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
        val current = _uiState.value as? WallUiState.Ready ?: return

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
        val current = _uiState.value as? WallUiState.Ready ?: return
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
        val current = _uiState.value as? WallUiState.Ready ?: return
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
                (_uiState.value as? WallUiState.Ready)?.let {
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
        val current = _uiState.value as? WallUiState.Ready ?: return

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
            val latest = _uiState.value as? WallUiState.Ready ?: return@launch
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
    fun newRoute() = startBlank("newRoute()")

    private fun startBlank(description: String) {
        if (_uiState.value !is WallUiState.Ready) return

        viewModelScope.launch {
            savedHolds = ""
            select(null)
            val latest = _uiState.value as? WallUiState.Ready ?: return@launch
            showAndPush(latest, emptyMap(), description)
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
        val current = _uiState.value as? WallUiState.Ready ?: return
        if (!current.modified) return

        val open = current.selectedRouteId
        if (open == null) startBlank("revertRoute()") else loadRoute(open)
    }

    private suspend fun clearDraft() {
        val wallId = (_uiState.value as? WallUiState.Ready)?.wallId ?: return
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

            val current = _uiState.value as? WallUiState.Ready ?: return@launch
            if (current.selectedRouteId != routeId) return@launch

            // Baseline first, so the clear that follows reads as unmodified
            // rather than as an edit of the route that has just gone.
            savedHolds = ""
            select(null)
            val latest = _uiState.value as? WallUiState.Ready ?: return@launch
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
        val current = _uiState.value as? WallUiState.Ready ?: return
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
     * Writes this wall's routes to [open]'s stream and closes it.
     *
     * Takes a stream rather than the file the person picked: a Uri, and what it
     * takes to turn one into bytes, belong to the Android framework, and putting
     * that here would make every test of this class need a ContentResolver.
     */
    fun exportRoutes(open: () -> OutputStream?) {
        val current = _uiState.value as? WallUiState.Ready ?: return
        val wallId = current.wallId ?: return

        viewModelScope.launch {
            _backupResult.value = try {
                val wall = walls.byId(wallId)
                    ?: throw IOException("wall $wallId is not stored")
                val document = routes.backup(wall)
                withContext(io) {
                    val stream = open() ?: throw IOException("no stream for the chosen file")
                    stream.use { it.write(document.toByteArray()) }
                }
                // The list the UI is showing is the list just written, so this
                // reports what someone can see rather than a second count that
                // could disagree with it.
                RouteBackupOutcome.Exported(savedRoutes.value.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "exportRoutes() failed", e)
                RouteBackupOutcome.Failed(null)
            }
        }
    }

    /** Adds the routes in [open]'s stream to this wall - see [RouteRepository.importInto]. */
    fun importRoutes(open: () -> InputStream?) {
        val current = _uiState.value as? WallUiState.Ready ?: return
        val wallId = current.wallId ?: return

        viewModelScope.launch {
            _backupResult.value = try {
                val wall = walls.byId(wallId)
                    ?: throw IOException("wall $wallId is not stored")
                val text = withContext(io) {
                    val stream = open() ?: throw IOException("no stream for the chosen file")
                    stream.use { it.readBytes() }
                }
                RouteBackupOutcome.Imported(
                    routes.importInto(
                        wall = wall,
                        file = RouteBackup.decode(text.decodeToString()),
                        currentFingerprint = current.wall.fingerprint
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RouteBackupException) {
                // Not an error to log loudly: picking the wrong file is an
                // ordinary thing to do, and the screen is about to say which.
                Log.i(TAG, "importRoutes() refused a file: ${e.problem}")
                RouteBackupOutcome.Failed(e.problem)
            } catch (e: Exception) {
                Log.e(TAG, "importRoutes() failed", e)
                RouteBackupOutcome.Failed(null)
            }
        }
    }

    /** Called once the outcome has been shown, so it is not shown twice. */
    fun clearBackupResult() {
        _backupResult.value = null
    }

    /**
     * Shows [holds] straight away and, while the controller is online, pushes
     * them in the background. Paused or out of reach, the edit is kept on
     * this device and goes up with the next change once back.
     *
     * The grid updates before the request completes: on a local network the
     * round trip is short, but waiting for it would make every tap feel
     * sticky. A failed push marks the controller out of reach, same as a
     * failed on/off toggle, rather than silently leaving the app and the wall
     * showing different things - and the work stays on screen.
     */
    private fun showAndPush(
        current: WallUiState.Ready,
        holds: Map<Int, HoldColor>,
        description: String
    ) {
        show(current, holds)
        if (current.controller is ControllerState.Online) push(current.wall, holds, description)
    }

    /**
     * Puts [holds] on screen and records them as the draft, without going
     * near the wall.
     */
    private fun show(current: WallUiState.Ready, holds: Map<Int, HoldColor>) {
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
    }

    private fun push(wall: Wall, holds: Map<Int, HoldColor>, description: String) {
        viewModelScope.launch {
            try {
                client.setHoldColors(pixelCount = wall.segmentSize, lit = holds.toHex())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$description failed", e)
                goOffline(e)
            }
        }
    }

    /**
     * The controller stopped answering. Everything on screen stays; what
     * changes is that nothing is on the wall as far as the app knows.
     */
    private fun goOffline(e: Exception) {
        val current = _uiState.value as? WallUiState.Ready ?: return
        // A request sent just before pausing can fail just after it. The
        // pause was asked for; the failure only confirms it.
        if (current.controller is ControllerState.Paused) return
        _uiState.value = current.copy(controller = ControllerState.Offline(problemFor(e)))
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
        val current = _uiState.value as? WallUiState.Ready ?: return
        val online = current.controller as? ControllerState.Online ?: return
        // A drag reports once per frame, and consecutive frames routinely land
        // on the same integer once the slider's float is truncated - a 2s drag
        // at 120Hz reports 250 times across at most 248 distinct values. The
        // write below would be suppressed anyway, since an unchanged copy
        // compares equal and StateFlow drops it, but establishing that costs a
        // structural comparison of the whole grid and route every frame. The
        // conflated send would collapse too. Neither is worth reaching.
        if (brightness == online.brightness) return
        // Moves with the finger. The request that follows is conflated, so the
        // slider stays smooth whatever the controller is keeping up with.
        _uiState.value = current.copy(controller = online.copy(brightness = brightness))
        requestedBrightness = brightness
        brightnessRequests.trySend(brightness)
    }

    fun toggleWall() {
        val current = _uiState.value as? WallUiState.Ready ?: return
        val online = current.controller as? ControllerState.Online ?: return
        if (online.busy) return
        _uiState.value = current.copy(controller = online.copy(busy = true))

        viewModelScope.launch {
            try {
                val status = client.setOn(on = !online.on)
                // Read again rather than reusing the state from before the
                // request, so edits made while it was in flight are kept.
                val latest = _uiState.value as? WallUiState.Ready ?: return@launch
                // WLED unfreezes every segment when it's switched on (see the
                // "unfreeze all segments when turning on" branch in json.cpp),
                // which drops the per-pixel route from the wall while the app
                // still shows it. Push the route again so the two agree.
                if (status.on && latest.litHolds.isNotEmpty()) {
                    client.setHoldColors(pixelCount = latest.wall.segmentSize, lit = latest.litHolds.toHex())
                }
                val settled = _uiState.value as? WallUiState.Ready ?: return@launch
                // Paused while this was in flight: the pause was asked for
                // last, and wins.
                if (settled.controller !is ControllerState.Online) return@launch
                _uiState.value = settled.copy(
                    controller = ControllerState.Online(on = status.on, brightness = status.brightness)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "toggleWall() failed", e)
                goOffline(e)
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
