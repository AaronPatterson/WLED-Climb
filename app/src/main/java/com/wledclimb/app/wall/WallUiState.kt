package com.wledclimb.app.wall

import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.grid.Wall

/**
 * UI-facing state of the wall screen.
 *
 * About what there is to show rather than about the network. A wall the app
 * knows can be shown, edited and saved with no controller in reach, so the
 * state that carries one is not named after being connected to anything - that
 * belongs to [Showing.link], which can say the app is deliberately detached.
 */
sealed interface WallUiState {
    data object Connecting : WallUiState

    /**
     * [litHolds] maps a grid position (`Wall.segmentIndexAt`) to the colour it's
     * showing; holds absent from it are off. This is the route currently on the
     * wall. Keyed by grid position, not by position along the LED strip - that's
     * what WLED's per-pixel commands address.
     *
     * [selectedColor] is what the next tapped hold will be painted in.
     *
     * [brightness] is WLED's master brightness and is independent of [on]:
     * switching the wall off leaves it where it was, so both are needed to
     * describe the wall rather than either alone.
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
     * [name] is the controller's own name, shown so the top bar says which
     * wall is being controlled. It becomes more than decoration once there is
     * more than one wall to be connected to.
     */
    data class Showing(
        val on: Boolean,
        val brightness: Int,
        val name: String,
        val wall: Wall,
        val wallId: Long? = null,
        val selectedRouteId: Long? = null,
        val modified: Boolean = false,
        val litHolds: Map<Int, HoldColor> = emptyMap(),
        val selectedColor: HoldColor = HoldColor.Red,
        val busy: Boolean = false
    ) : WallUiState

    data class Error(val problem: WallProblem) : WallUiState
}
