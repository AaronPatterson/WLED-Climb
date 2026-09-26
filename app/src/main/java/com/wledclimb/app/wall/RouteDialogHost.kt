package com.wledclimb.app.wall

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wledclimb.app.R
import com.wledclimb.app.storage.StoredRoute

/**
 * What someone was trying to do when unsaved work interrupted them.
 *
 * Described rather than captured. This used to be a `() -> Unit` held in
 * Compose state, which meant the interrupted action was opaque - it could not
 * be inspected, compared, saved across a configuration change, or reasoned
 * about while reading the code around it. Two of those matter in practice and
 * the third is why a bug hid here: the branch that resumed it reached for the
 * wrong save path, and nothing about a stored lambda made that visible.
 */
internal sealed interface PendingAction {
    data class Open(val routeId: Long) : PendingAction

    data object StartNew : PendingAction
}

/** Which route dialog is on screen, and what it needs to know. */
internal sealed interface RouteDialog {

    /**
     * Naming work that has never been saved.
     *
     * [then] is what to resume once it has a name, so that agreeing to save
     * before switching routes does not also lose the route being switched to.
     */
    data class Name(val then: PendingAction?) : RouteDialog

    /** Naming a copy of [route], leaving the original as it is. */
    data class NameCopy(val route: StoredRoute) : RouteDialog

    data class Rename(val route: StoredRoute) : RouteDialog

    data class Delete(val route: StoredRoute) : RouteDialog

    /** Confirming that unsaved edits go back to the route as saved. */
    data object Reset : RouteDialog

    /** Asking whether to save before [then] takes the work off the wall. */
    data class UnsavedChanges(val then: PendingAction) : RouteDialog
}

/**
 * Shows whichever route dialog is open.
 *
 * All of them in one place because they share a shape - each asks a question,
 * does one thing, and closes - and because scattered through the screen they
 * were easy to read as independent when several of them hand off to each
 * other.
 *
 * [onRun] performs an interrupted action and closes; [onShow] replaces this
 * dialog with another, which is how answering "save" to unsaved changes
 * reaches the naming dialog.
 */
@Composable
internal fun RouteDialogHost(
    dialog: RouteDialog,
    openRoute: StoredRoute?,
    onDismiss: () -> Unit,
    onShow: (RouteDialog) -> Unit,
    onRun: (PendingAction) -> Unit,
    onSaveRoute: (name: String, routeId: Long?) -> Unit,
    onRenameRoute: (Long, String) -> Unit,
    onDeleteRoute: (Long) -> Unit,
    onRevertRoute: () -> Unit
) {
    when (dialog) {
        is RouteDialog.Name -> SaveRouteDialog(
            title = stringResource(R.string.routes_save_title),
            initialName = "",
            onDismiss = onDismiss,
            onSave = { name ->
                onSaveRoute(name, null)
                dialog.then?.let(onRun) ?: onDismiss()
            }
        )

        is RouteDialog.NameCopy -> SaveRouteDialog(
            title = stringResource(R.string.routes_save_as_title),
            initialName = dialog.route.name,
            onDismiss = onDismiss,
            onSave = { name ->
                onSaveRoute(name, null)
                onDismiss()
            }
        )

        is RouteDialog.Rename -> RenameRouteDialog(
            initialName = dialog.route.name,
            onDismiss = onDismiss,
            onRename = { name ->
                onRenameRoute(dialog.route.id, name)
                onDismiss()
            }
        )

        is RouteDialog.Delete -> DeleteRouteDialog(
            name = dialog.route.name,
            onDismiss = onDismiss,
            onDelete = {
                onDeleteRoute(dialog.route.id)
                onDismiss()
            }
        )

        RouteDialog.Reset -> ResetRouteDialog(
            routeName = openRoute?.name,
            onDismiss = onDismiss,
            onReset = {
                onRevertRoute()
                onDismiss()
            }
        )

        is RouteDialog.UnsavedChanges -> UnsavedChangesDialog(
            routeName = openRoute?.name,
            onCancel = onDismiss,
            onDiscard = { onRun(dialog.then) },
            onSave = {
                if (openRoute == null) {
                    // Never saved, so it has to be named first, and what it
                    // interrupted comes along to be resumed afterwards.
                    onShow(RouteDialog.Name(then = dialog.then))
                } else {
                    // A route that already has a name is written without
                    // asking, the same rule as the save button follows.
                    onSaveRoute(openRoute.name, openRoute.id)
                    onRun(dialog.then)
                }
            }
        )
    }
}
