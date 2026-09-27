package com.wledclimb.app.wall

import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.grid.Wall

/**
 * UI-facing state of the wall screen.
 *
 * Whether the controller can be reached is a property of [Ready], not a shape
 * of its own. It used to be: a wall that could not be reached was an [Error],
 * which took the grid away - and with the wall's shape and routes stored, there
 * is nothing about editing a route that needs the controller at all.
 */
sealed interface WallUiState {

    /**
     * Finding the wall to show: the stored one if this device has reached it
     * before, otherwise whatever the controller says.
     */
    data object Loading : WallUiState

    /**
     * A wall on screen, whether or not its controller answers - see
     * [controller].
     *
     * [litHolds] maps a grid position (`Wall.segmentIndexAt`) to the colour it's
     * showing; holds absent from it are off. This is the route on screen, which
     * is on the wall too when [applied]. Keyed by grid position, not by position
     * along the LED strip - that's what WLED's per-pixel commands address.
     *
     * [selectedColor] is what the next tapped hold will be painted in.
     *
     * [wallId] is the row this wall was stored as, and is null when storing it
     * failed. That is survivable rather than fatal: a wall that cannot be
     * written to the database can still be lit, so the grid stays usable and
     * only saving routes is unavailable.
     *
     * [selectedRouteId] is the saved route being edited, or null for holds that
     * have not been saved as one. Editing a loaded route does not clear it: the
     * edits belong to that route until they are saved over it or saved as a new
     * one, which is what makes "save" mean something different from "save as".
     *
     * [modified] is true when what is on the wall differs from the route it
     * came from, or from nothing at all when no route is open. It is derived
     * by comparing against the route as saved, so undoing an edit back to the
     * original clears it rather than leaving the wall looking dirty forever.
     *
     * [name] is the controller's own name, as last reported, shown so the top
     * bar says which wall is being controlled. It becomes more than decoration
     * once there is more than one wall to be connected to.
     *
     * [applied] is true when the wall is showing [litHolds] and follows each
     * edit to them. It is what makes applying a deliberate act: an edit with
     * this false changes the screen and the draft, and leaves the wall with
     * whatever someone else put there. Applying sets it, and it clears when
     * the work on screen becomes something else - another route opened, a new
     * one started - or when the app opens or the controller is lost, because
     * the app cannot read the wall back to know whether what it last sent is
     * still there.
     *
     * [autoApply] is this device's setting that every change goes straight to
     * the wall, as though each one were applied. See `WledSettings.autoApply`.
     */
    data class Ready(
        val name: String,
        val wall: Wall,
        val controller: ControllerState,
        val wallId: Long? = null,
        val selectedRouteId: Long? = null,
        val modified: Boolean = false,
        val litHolds: Map<Int, HoldColor> = emptyMap(),
        val selectedColor: HoldColor = HoldColor.Red,
        val applied: Boolean = false,
        val autoApply: Boolean = true
    ) : WallUiState {

        /** A power change is in flight, and the controls wait for it. */
        val busy: Boolean get() = (controller as? ControllerState.Online)?.busy == true
    }

    /**
     * Nothing to show: there is no stored wall to fall back on, and the
     * controller could not be reached or could not be used.
     */
    data class Error(val problem: WallProblem) : WallUiState
}
