package com.wledclimb.app.wall

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wledclimb.app.R

/**
 * Names a route that does not have a name yet.
 *
 * Only reached when saving would create a route - the first save of a wall
 * nobody has saved before, or saving the open route as a second one. Saving
 * over a route that already has a name does not come through here, because
 * renaming it is a different intention and has its own action.
 */
@Composable
fun SaveRouteDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routes_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.routes_name_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text(stringResource(R.string.routes_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) }
        }
    )
}

@Composable
fun RenameRouteDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routes_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.routes_name_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name.trim()) }) {
                Text(stringResource(R.string.routes_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) }
        }
    )
}

/**
 * Confirms a delete.
 *
 * Asked for because deleting is the one route action that cannot be undone -
 * there is no bin to fish it back out of, and the holds are not enough to
 * rebuild it from once the wall moves on.
 */
@Composable
fun DeleteRouteDialog(
    name: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routes_delete_title, name)) },
        text = { Text(stringResource(R.string.routes_delete_body)) },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.routes_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) }
        }
    )
}

/**
 * Asked before switching away from unsaved work.
 *
 * Three answers rather than two, because "no" is ambiguous here: not saving
 * and not switching are different intentions, and a dialog that treats them as
 * one will eventually throw away a route on someone's behalf. Cancel is the
 * dismissal, so tapping outside keeps the work.
 */
@Composable
fun UnsavedChangesDialog(
    routeName: String?,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (routeName == null) {
                    stringResource(R.string.routes_discard_untitled_title)
                } else {
                    stringResource(R.string.routes_discard_title, routeName)
                }
            )
        },
        text = { Text(stringResource(R.string.routes_discard_body)) },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.routes_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.routes_discard)) }
        }
    )
}

/**
 * Confirms throwing away unsaved edits.
 *
 * Asked because reset is not undoable - the draft is the only copy of that
 * work, and a mis-tap in a sheet that also holds Save and New should not be
 * able to destroy it. The wording says what comes back rather than what goes,
 * since that is the part someone is checking.
 */
@Composable
fun ResetRouteDialog(
    routeName: String?,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (routeName == null) {
                    stringResource(R.string.routes_reset_untitled_title)
                } else {
                    stringResource(R.string.routes_reset_title, routeName)
                }
            )
        },
        text = {
            Text(
                stringResource(
                    if (routeName == null) {
                        R.string.routes_reset_untitled_body
                    } else {
                        R.string.routes_reset_body
                    }
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.routes_reset)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) }
        }
    )
}
