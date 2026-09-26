package com.wledclimb.app.wall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R
import com.wledclimb.app.storage.StoredRoute

/**
 * The saved routes for a wall: pick one to put it on the wall, or save what is
 * up there now.
 *
 * Deliberately knows nothing about how it is presented. Today it is shown in a
 * sheet; navigation.md has it becoming the list half of a list-detail layout,
 * permanently beside the grid on a tablet. That is a change of arrangement
 * rather than of this composable.
 *
 * [staleFingerprint] is the wall's fingerprint as it is now. A route recorded
 * against a different one was drawn on a wall that has since changed shape, so
 * it is flagged rather than hidden - it still opens, minus the holds that no
 * longer exist.
 */
@Composable
fun RoutesPanel(
    routes: List<StoredRoute>,
    selectedRouteId: Long?,
    currentFingerprint: String,
    canSave: Boolean,
    modified: Boolean,
    onLoad: (Long) -> Unit,
    onNew: () -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    onRename: (StoredRoute) -> Unit,
    onSaveAsNew: (StoredRoute) -> Unit,
    onDelete: (StoredRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.routes_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNew) {
                Text(stringResource(R.string.routes_new))
            }
            TextButton(onClick = onSave, enabled = canSave) {
                Text(stringResource(R.string.routes_save))
            }
        }

        // Only while there is something to reset. It explains the dot in the
        // top bar as well as offering the way out, which is why it says what
        // the state is rather than being a bare button.
        if (modified) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.routes_unsaved_changes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRevert) {
                    Text(stringResource(R.string.routes_reset))
                }
            }
        }

        HorizontalDivider()

        if (routes.isEmpty()) {
            Text(
                text = stringResource(
                    if (canSave) R.string.routes_empty else R.string.routes_unavailable
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )
            return@Column
        }

        LazyColumn(
            // Bounded so a long list cannot push the save button off a phone,
            // and so the sheet does not open full height for three routes.
            modifier = Modifier.heightIn(max = 360.dp)
        ) {
            items(routes, key = { it.id }) { route ->
                RouteRow(
                    route = route,
                    selected = route.id == selectedRouteId,
                    stale = route.wallFingerprint != currentFingerprint,
                    onLoad = { onLoad(route.id) },
                    onRename = { onRename(route) },
                    // Only where it means something: a second copy of a route
                    // is only worth offering while there are changes that would
                    // otherwise overwrite the first.
                    onSaveAsNew = if (route.id == selectedRouteId && modified) {
                        { onSaveAsNew(route) }
                    } else {
                        null
                    },
                    onDelete = { onDelete(route) }
                )
            }
        }
    }
}

@Composable
private fun RouteRow(
    route: StoredRoute,
    selected: Boolean,
    stale: Boolean,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onSaveAsNew: (() -> Unit)?,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onLoad)
                .padding(start = 24.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                if (stale) {
                    // Kept in the list rather than hidden: a route outliving a
                    // change to its wall is the case to handle, not to avoid.
                    Text(
                        text = stringResource(R.string.routes_wall_changed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            RouteOverflow(menuOpen = menuOpen, onDismiss = { menuOpen = false }, onOpen = { menuOpen = true }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routes_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                onSaveAsNew?.let { saveAsNew ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.routes_save_new)) },
                        onClick = {
                            menuOpen = false
                            saveAsNew()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routes_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** The per-row overflow button and its menu, kept out of [RouteRow]'s shape. */
@Composable
private fun RouteOverflow(
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        IconButton(onClick = onOpen) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.routes_more)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) { content() }
    }
}
