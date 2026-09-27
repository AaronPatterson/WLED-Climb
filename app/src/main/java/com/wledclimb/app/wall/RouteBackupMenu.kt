package com.wledclimb.app.wall

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wledclimb.app.R
import com.wledclimb.app.storage.RouteBackupProblem
import com.wledclimb.app.storage.RouteImport

/** The two file pickers, ready to open. */
internal class RouteBackupLaunchers(
    val export: () -> Unit,
    val import: () -> Unit
)

/**
 * Wires the export and import menu items to the system file picker.
 *
 * The picker rather than a path the app chooses. Routes are the person's, and a
 * backup is only a backup if it lands somewhere they can reach and keep - their
 * own Drive, Downloads, a memory card - which is exactly what the document
 * picker is for. It also means the app needs no storage permission at all: it
 * is handed one file, chosen deliberately, and can see nothing else.
 */
@Composable
internal fun rememberRouteBackup(
    wallName: String,
    onExportTo: (Uri) -> Unit,
    onImportFrom: (Uri) -> Unit
): RouteBackupLaunchers {
    val suggestedName = stringResource(R.string.routes_backup_file_name, wallName)

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportTo) }

    // Null when the picker was dismissed, which is not a failure and deserves
    // no message - someone changed their mind.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportFrom) }

    return remember(suggestedName, exportPicker, importPicker) {
        RouteBackupLaunchers(
            export = { exportPicker.launch(suggestedName) },
            // Everything, rather than a JSON filter. What a provider calls a
            // file is not reliable - the same backup arrives as application/json
            // from one and octet-stream from another - and a filter that greys
            // out someone's own backup is worse than one that shows too much.
            // The file's contents are checked either way, which is the real
            // gate: see RouteBackup.decode.
            import = { importPicker.launch(arrayOf("*/*")) }
        )
    }
}

/** Says how the last export or import went - see [RouteBackupOutcome]. */
@Composable
internal fun RouteBackupOutcomeDialog(
    outcome: RouteBackupOutcome,
    onDismiss: () -> Unit
) {
    val title = when (outcome) {
        is RouteBackupOutcome.Exported -> R.string.routes_export_done_title
        is RouteBackupOutcome.Imported -> R.string.routes_import_done_title
        is RouteBackupOutcome.Failed -> R.string.routes_backup_failed_title
    }

    val body = when (outcome) {
        is RouteBackupOutcome.Exported -> pluralStringResource(
            R.plurals.routes_export_done_body,
            outcome.routeCount,
            outcome.routeCount
        )

        is RouteBackupOutcome.Imported -> importedBody(outcome.result)

        is RouteBackupOutcome.Failed -> stringResource(
            when (outcome.problem) {
                RouteBackupProblem.Unreadable -> R.string.routes_backup_failed_unreadable
                RouteBackupProblem.NotABackup -> R.string.routes_backup_failed_not_a_backup
                RouteBackupProblem.TooNew -> R.string.routes_backup_failed_too_new
                RouteBackupProblem.WrongWall -> R.string.routes_backup_failed_wrong_wall
                RouteBackupProblem.Empty -> R.string.routes_backup_failed_empty
                null -> R.string.routes_backup_failed_unknown
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(title)) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.routes_backup_close))
            }
        }
    )
}

/**
 * What an import did, in sentences.
 *
 * Every part of it, not just the total. Routes that were skipped and routes
 * that had to be renamed are the two things someone would otherwise discover
 * later by finding a list that does not look the way they expected - and an
 * import that says only "7 added" after adding nothing is how a backup gets
 * mistrusted.
 */
@Composable
private fun importedBody(result: RouteImport): String {
    if (result.added == 0) return stringResource(R.string.routes_import_nothing_new)

    val lines = mutableListOf(
        pluralStringResource(R.plurals.routes_import_added, result.added, result.added)
    )
    if (result.skipped > 0) {
        lines += pluralStringResource(
            R.plurals.routes_import_skipped,
            result.skipped,
            result.skipped
        )
    }
    if (result.renamed > 0) {
        lines += pluralStringResource(
            R.plurals.routes_import_renamed,
            result.renamed,
            result.renamed
        )
    }
    return lines.joinToString("\n")
}
