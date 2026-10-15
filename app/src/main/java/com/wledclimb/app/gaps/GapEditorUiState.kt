package com.wledclimb.app.gaps

import com.wledclimb.app.grid.GapCell
import com.wledclimb.app.grid.GapLayout

/** UI-facing state of the gap editor. */
sealed interface GapEditorUiState {

    /** Reading the gap file. It is always read fresh - see docs/walls-and-routes.md. */
    data object Loading : GapEditorUiState

    /** Nothing to edit: the controller could not be read. */
    data class LoadFailed(val problem: GapEditorProblem) : GapEditorUiState

    /**
     * The gap file on screen, being edited.
     *
     * [original] is what the controller has, and [layout] is what is on
     * screen; the difference between them is what saving would change.
     *
     * [brush] is what the next tapped position will be marked as, the way a
     * colour is picked before painting a route.
     *
     * [saving] is an upload in flight. [problem] is why the last save did not
     * work, and stays until the next edit or save.
     */
    data class Editing(
        val original: GapLayout,
        val layout: GapLayout,
        val brush: GapCell = GapCell.Hold,
        val saving: Boolean = false,
        val problem: GapEditorProblem? = null
    ) : GapEditorUiState {

        /** Whether there is anything to save, or to lose by leaving. */
        val modified: Boolean get() = layout != original
    }

    /**
     * Saved, and waiting for the controller to come back from the restart that
     * makes it use the new layout.
     */
    data object Restarting : GapEditorUiState

    /** Saved and in use. There is nothing left to do here. */
    data object Saved : GapEditorUiState
}
