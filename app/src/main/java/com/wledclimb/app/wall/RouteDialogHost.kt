package com.wledclimb.app.wall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
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
     * Routes are named by id rather than held as a copy.
     *
     * A copy taken when the dialog opened goes stale the moment the route is
     * renamed or saved underneath it - the delete confirmation would go on
     * naming the route by whatever it was called when the menu was tapped. An
     * id is looked up against the current list every time it is drawn, and
     * being a number is also what lets the open dialog be written down and
     * restored after a rotation.
     */

    /**
     * Naming work that has never been saved.
     *
     * [then] is what to resume once it has a name, so that agreeing to save
     * before switching routes does not also lose the route being switched to.
     */
    data class Name(val then: PendingAction?) : RouteDialog

    /** Naming a copy of a route, leaving the original as it is. */
    data class NameCopy(val routeId: Long) : RouteDialog

    data class Rename(val routeId: Long) : RouteDialog

    data class Delete(val routeId: Long) : RouteDialog

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
    routes: List<StoredRoute>,
    openRoute: StoredRoute?,
    onDismiss: () -> Unit,
    onShow: (RouteDialog) -> Unit,
    onRun: (PendingAction) -> Unit,
    onSaveRoute: (name: String, routeId: Long?) -> Unit,
    onRenameRoute: (Long, String) -> Unit,
    onDeleteRoute: (Long) -> Unit,
    onRevertRoute: () -> Unit
) {
    // A dialog naming a route that has since gone - deleted here or from
    // another device - has nothing left to ask about.
    val subject = when (dialog) {
        is RouteDialog.NameCopy -> routes.firstOrNull { it.id == dialog.routeId }
        is RouteDialog.Rename -> routes.firstOrNull { it.id == dialog.routeId }
        is RouteDialog.Delete -> routes.firstOrNull { it.id == dialog.routeId }
        else -> null
    }
    if (dialog is RouteDialog.NameCopy || dialog is RouteDialog.Rename ||
        dialog is RouteDialog.Delete
    ) {
        if (subject == null) {
            onDismiss()
            return
        }
    }

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
            initialName = subject!!.name,
            onDismiss = onDismiss,
            onSave = { name ->
                onSaveRoute(name, null)
                onDismiss()
            }
        )

        is RouteDialog.Rename -> RenameRouteDialog(
            initialName = subject!!.name,
            onDismiss = onDismiss,
            onRename = { name ->
                onRenameRoute(dialog.routeId, name)
                onDismiss()
            }
        )

        is RouteDialog.Delete -> DeleteRouteDialog(
            name = subject!!.name,
            onDismiss = onDismiss,
            onDelete = {
                onDeleteRoute(dialog.routeId)
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

/**
 * Writes the open dialog down, so a rotation does not close it.
 *
 * Hand-rolled rather than parcelised: every case is a number, a nullable
 * number or nothing at all, which is cheaper to save directly than to add a
 * plugin and annotations for.
 */
internal val RouteDialogSaver: Saver<RouteDialog?, Any> = listSaver(
    save = { dialog ->
        when (dialog) {
            null -> emptyList()
            is RouteDialog.Name -> listOf("name", saveAction(dialog.then))
            is RouteDialog.NameCopy -> listOf("copy", dialog.routeId)
            is RouteDialog.Rename -> listOf("rename", dialog.routeId)
            is RouteDialog.Delete -> listOf("delete", dialog.routeId)
            RouteDialog.Reset -> listOf("reset")
            is RouteDialog.UnsavedChanges -> listOf("unsaved", saveAction(dialog.then))
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "name" -> RouteDialog.Name(restoreAction(saved[1]))
            "copy" -> RouteDialog.NameCopy(saved[1] as Long)
            "rename" -> RouteDialog.Rename(saved[1] as Long)
            "delete" -> RouteDialog.Delete(saved[1] as Long)
            "reset" -> RouteDialog.Reset
            "unsaved" -> RouteDialog.UnsavedChanges(restoreAction(saved[1])!!)
            else -> null
        }
    }
)

/** -1 stands in for "nothing was interrupted", which only Name can mean. */
private fun saveAction(action: PendingAction?): Long = when (action) {
    null -> -1L
    PendingAction.StartNew -> -2L
    is PendingAction.Open -> action.routeId
}

private fun restoreAction(saved: Any): PendingAction? = when (val id = saved as Long) {
    -1L -> null
    -2L -> PendingAction.StartNew
    else -> PendingAction.Open(id)
}
