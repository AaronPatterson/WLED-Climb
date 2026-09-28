package com.wledclimb.app.wall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.net.Uri
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R
import com.wledclimb.app.network.MAX_BRIGHTNESS
import com.wledclimb.app.network.MIN_USABLE_BRIGHTNESS

/**
 * Names the wall and carries the two controls that belong to the whole wall
 * rather than to a route: power and brightness.
 *
 * Both used to sit in the content below, where they competed with the grid for
 * attention and for space. Up here they stay reachable from anywhere that shows
 * a wall, and the screen beneath is free to be about routes.
 *
 * Brightness hides behind its icon rather than sitting open. A slider wide
 * enough for a six-year-old costs a row of vertical space, which on a phone in
 * portrait is a row the grid wants more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallTopBar(
    modifier: Modifier = Modifier,
    name: String,
    controller: ControllerState,
    enabled: Boolean,
    // Hoisted, because tapping anywhere below has to close it and the content
    // down there cannot reach state that lives in here.
    brightnessOpen: Boolean,
    onBrightnessOpenChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    /** Asks the controller again, after it was out of reach or paused. */
    onReconnect: () -> Unit,
    /** Leaves the controller alone until [onReconnect]. */
    onPause: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onChangeController: () -> Unit,
    onToggleRoutes: () -> Unit,
    /** False when this wall could not be stored, so it has no routes to move. */
    canBackupRoutes: Boolean,
    /** False when there is nothing saved yet - an empty file is not a backup. */
    canExportRoutes: Boolean,
    onExportRoutes: (Uri) -> Unit,
    onImportRoutes: (Uri) -> Unit,
) {
    val unsavedDescription = stringResource(R.string.routes_unsaved_changes)
    // Power and brightness are questions for the controller, and have no
    // answer while it is being asked or cannot be reached.
    val online = controller as? ControllerState.Online
    val paused = controller == ControllerState.Paused
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    val backup = rememberRouteBackup(
        wallName = name,
        onExportTo = onExportRoutes,
        onImportFrom = onImportRoutes
    )

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }

    TopAppBar(
        modifier = modifier,
        title = {
                // The wall's name, and only that. The route moved down onto
                // the screen it belongs to, where a full-width row has room
                // for a name this bar was truncating at about twenty
                // characters.
                //
                // Still opens the routes on a tap, because a title that
                // answers one is a far bigger target than the icon beside it.
                Text(
                    text = name,
                    // titleLarge is what Material gives an app bar title. It
                    // was a size down from that to leave room for the route
                    // name beside it, and the route name has since moved out.
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            enabled = enabled,
                            onClickLabel = stringResource(R.string.routes_open),
                            onClick = onToggleRoutes
                        )
                        // Only enough to give the ripple a shape. More read
                        // as a gap left for something that is no longer there.
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            },
            navigationIcon = {
                // The left of an app bar is where navigation lives, and the
                // routes list is the only thing this bar navigates to. It used
                // to be a hamburger, which was wrong twice over: that icon
                // promises a navigation drawer, and there is none, and it put
                // the app's settings in the position someone reaches for to go
                // somewhere.
                IconButton(onClick = onToggleRoutes, enabled = enabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_routes),
                        contentDescription = stringResource(R.string.routes_open),
                        // Larger than the default. The power button wears a
                        // ring and the brightness glyph is dense, so a plain
                        // 24dp icon between them reads as the smaller thing
                        // rather than the equal one.
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            actions = {
                // Absent on the practice wall rather than disabled. Both ask
                // something of a controller, and a greyed pair of controls
                // invites working out what is wrong with them when nothing is.
                if (controller != ControllerState.Demo) {
                    IconButton(
                        onClick = { onBrightnessOpenChange(!brightnessOpen) },
                        enabled = enabled && online != null
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_brightness),
                            contentDescription = stringResource(R.string.wall_brightness)
                        )
                    }
                    PowerButton(
                        controller = controller,
                        enabled = enabled,
                        onToggle = onToggle,
                        onReconnect = onReconnect,
                        onPause = onPause
                    )
                }

                // Last, and a different shape from the two beside it: these are
                // things you do to the wall, this is a menu about the app.
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.wall_menu),
                        modifier = Modifier.size(28.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Same treatment as the row menu in the routes panel: a
                    // glyph, a larger label and a taller row. A menu opened
                    // rarely is exactly the one worth being able to read
                    // without stopping to aim.
                    // Routes first. Moving them off a phone is the thing
                    // someone comes to this menu wanting, where the controller
                    // is set once and About is read once.
                    DropdownMenuItem(
                        enabled = canBackupRoutes && canExportRoutes,
                        text = {
                            Text(
                                text = stringResource(R.string.routes_export),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_export),
                                contentDescription = null
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        onClick = {
                            menuOpen = false
                            backup.export()
                        }
                    )
                    DropdownMenuItem(
                        enabled = canBackupRoutes,
                        text = {
                            Text(
                                text = stringResource(R.string.routes_import),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_import),
                                contentDescription = null
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        onClick = {
                            menuOpen = false
                            backup.import()
                        }
                    )

                    // These act on the app rather than on the routes.
                    HorizontalDivider()

                    // The same as a long press on the power button, which
                    // has nothing on screen to say it exists. This is where
                    // someone looking for it will find it, and a menu item is
                    // an ordinary action to a screen reader where a long
                    // press is not.
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.wall_work_offline),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_pause),
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            // Display only: the row is the control, and a
                            // second target inside it would toggle twice.
                            Checkbox(checked = paused, onCheckedChange = null)
                        },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        // The checkbox has no handler, so it reports no
                        // state of its own; the row says it instead.
                        modifier = Modifier.semantics {
                            role = Role.Checkbox
                            toggleableState = ToggleableState(paused)
                        },
                        // An answer on its way would undo the pause - see
                        // WallViewModel.pause.
                        // Paused keeps it live because the row is how you
                        // come back. Offline disables it: see canPause in
                        // PowerButton. The box stays unchecked there, because
                        // working offline is then circumstance rather than
                        // something anyone chose.
                        enabled = controller is ControllerState.Online || paused,
                        onClick = {
                            menuOpen = false
                            if (paused) onReconnect() else onPause()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.wall_change_controller),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_controller),
                                contentDescription = null
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        onClick = {
                            menuOpen = false
                            onChangeController()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.about_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_about),
                                contentDescription = null
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        onClick = {
                            menuOpen = false
                            aboutOpen = true
                        }
                    )
                }
            }
        )
}
