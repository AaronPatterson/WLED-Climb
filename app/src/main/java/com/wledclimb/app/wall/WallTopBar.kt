package com.wledclimb.app.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    on: Boolean,
    brightness: Int,
    enabled: Boolean,
    // Hoisted, because tapping anywhere below has to close it and the content
    // down there cannot reach state that lives in here.
    brightnessOpen: Boolean,
    onBrightnessOpenChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onChangeController: () -> Unit,
    onToggleRoutes: () -> Unit,
) {
    val unsavedDescription = stringResource(R.string.routes_unsaved_changes)
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

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
                IconButton(
                    onClick = { onBrightnessOpenChange(!brightnessOpen) },
                    enabled = enabled
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_brightness),
                        contentDescription = stringResource(R.string.wall_brightness)
                    )
                }
                // The whole button lights up rather than just the glyph. A
                // tinted outline was too quiet to answer "is the wall on?" from
                // across a garage, which is the one question this control exists
                // to answer without being tapped.
                //
                // The label still says which way it will go, because colour
                // alone is not an answer for anyone who cannot see it.
                val statusColour = if (on) WallStatusColors.on else WallStatusColors.off
                IconButton(onClick = onToggle, enabled = enabled) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            // Filled when on, hollow when off: the difference
                            // reads at a glance and does not rely on telling two
                            // colours apart.
                            .background(if (on) statusColour else Color.Transparent)
                            .border(width = 2.dp, color = statusColour, shape = CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_power),
                            contentDescription = stringResource(
                                if (on) R.string.wall_turn_off else R.string.wall_turn_on
                            ),
                            // On a filled circle the glyph has to contrast with
                            // the fill, not match it.
                            tint = if (on) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                statusColour
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Last, and a different shape from the two beside it: these are
                // things you do to the wall, this is a menu about the app.
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.wall_menu)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wall_change_controller)) },
                        onClick = {
                            menuOpen = false
                            onChangeController()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.about_title)) },
                        onClick = {
                            menuOpen = false
                            aboutOpen = true
                        }
                    )
                }
            }
        )
}
