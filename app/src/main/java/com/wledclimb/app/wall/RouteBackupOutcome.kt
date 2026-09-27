package com.wledclimb.app.wall

import com.wledclimb.app.storage.RouteBackupProblem
import com.wledclimb.app.storage.RouteImport

/**
 * How the last export or import went, so the screen can say so.
 *
 * Reported rather than silent. Both actions are one tap followed by a file
 * picker, after which nothing visible happens on success - an import adds rows
 * to a list that may be scrolled away, and an export writes a file the app
 * never shows. Without an answer, the only way to find out whether a backup
 * worked is to need it.
 */
sealed interface RouteBackupOutcome {

    data class Exported(val routeCount: Int) : RouteBackupOutcome

    data class Imported(val result: RouteImport) : RouteBackupOutcome

    /**
     * [problem] is null when the file was fine as far as the app could tell and
     * something else went wrong - no permission for the chosen file, storage
     * full, the picker handing back something unreadable.
     */
    data class Failed(val problem: RouteBackupProblem?) : RouteBackupOutcome
}
