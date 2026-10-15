package com.wledclimb.app.gaps

/**
 * Why the gap file could not be read or saved.
 *
 * A type rather than a message, for the same reasons as
 * [com.wledclimb.app.wall.WallProblem]: the copy lives in strings.xml, and
 * tests assert on what went wrong rather than on wording.
 */
sealed interface GapEditorProblem {

    /** The controller didn't answer. Nothing was read, or nothing was saved. */
    data object Unreachable : GapEditorProblem

    /** It answered, but isn't a WLED controller with a 2D matrix set up. */
    data object NotAWledMatrix : GapEditorProblem

    /**
     * WLED refused the upload because its settings are locked with a PIN.
     * Unlocking them in WLED's own settings page unlocks them for the app too.
     */
    data object Locked : GapEditorProblem

    /**
     * The gap file was written, but the controller could not be told to
     * restart and so is still drawing the old layout. Saving again writes the
     * same file and asks again.
     */
    data object NotRestarted : GapEditorProblem
}
