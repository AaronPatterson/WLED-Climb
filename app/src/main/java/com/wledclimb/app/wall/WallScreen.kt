package com.wledclimb.app.wall

import com.wledclimb.app.palette.HoldColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.saveable.rememberSaveable
import android.net.Uri
import com.wledclimb.app.BuildConfig
import com.wledclimb.app.R
import com.wledclimb.app.grid.fingerprint
import com.wledclimb.app.storage.StoredRoute
import com.wledclimb.app.grid.Wall

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun WallScreen(
    state: WallUiState,
    routes: List<StoredRoute>,
    onToggle: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onHoldTap: (segmentIndex: Int) -> Unit,
    onColorSelect: (HoldColor) -> Unit,
    onClearWall: () -> Unit,
    onRetry: () -> Unit,
    onChangeController: () -> Unit,
    onNewRoute: () -> Unit,
    onRevertRoute: () -> Unit,
    onLoadRoute: (Long) -> Unit,
    onSaveRoute: (name: String, routeId: Long?) -> Unit,
    onRenameRoute: (Long, String) -> Unit,
    onDeleteRoute: (Long) -> Unit,
    onExportRoutes: (Uri) -> Unit,
    onImportRoutes: (Uri) -> Unit
) {
    var brightnessOpen by remember { mutableStateOf(false) }
    // One dialog at a time, named by what it is asking. Seven flags and two
    // stored lambdas said the same thing less clearly, and allowed states that
    // cannot happen - two dialogs at once, or an action waiting to resume with
    // nothing on screen to resume it.
    var dialog by rememberSaveable(stateSaver = RouteDialogSaver) {
        mutableStateOf<RouteDialog?>(null)
    }
    // Measured rather than assumed, so the floating brightness row sits under
    // the bar whatever height the bar turns out to be.
    var topBarHeight by remember { mutableIntStateOf(0) }

    // Opens on the wall, not on the list. Launching into a route picker puts a
    // menu between someone and the thing they opened the app to use - and the
    // route they were last on has already been restored by the time this shows,
    // so the list would be covering the answer to the question it asks.
    //
    // The list is seeded behind it rather than replaced by it, so back from the
    // wall reaches the routes on a phone instead of leaving the app.
    // Saving a route that already has a name just saves it; work with no name
    // yet has to be given one. Defined once because the bar and the routes
    // panel both offer it, and two copies would eventually disagree.
    val openRoute = (state as? WallUiState.Connected)
        ?.let { s -> routes.firstOrNull { it.id == s.selectedRouteId } }
    val save = {
        if (openRoute == null) {
            dialog = RouteDialog.Name(then = null)
        } else {
            onSaveRoute(openRoute.name, openRoute.id)
        }
    }

    // Where there is room for both panes, the routes list can still be put
    // away - a wall is worth more width than a list of names, and editing one
    // is what the extra space is for.
    //
    // Done by overriding how many panes the scaffold may show rather than by
    // navigating: navigating picks which pane is current, and where both fit
    // that changes nothing. Survives rotation, because turning a tablet is not
    // a request to bring the list back.
    var routesCollapsed by rememberSaveable { mutableStateOf(false) }
    val roomForBoth = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val directive = if (routesCollapsed) {
        roomForBoth.copy(maxHorizontalPartitions = 1)
    } else {
        roomForBoth
    }
    // False on a phone, where there was never a second pane to collapse and
    // the button keeps its original job of moving between them.
    val showsBothPanes = roomForBoth.maxHorizontalPartitions > 1

    // Keyed on the collapse, which is what makes it take effect at all. The
    // scaffold has no directive of its own - it reads the navigator's - and the
    // navigator is remembered without the directive among its keys, so handing
    // it a new one after it exists changes nothing. Keying here builds a fresh
    // navigator instead, which is the only way in to a value it will honour.
    //
    // Recreating costs the pane back stack, which is why the history below is
    // seeded rather than accumulated: whichever navigator is in use, back from
    // the wall reaches the routes and the wall is what opens.
    val navigator = key(routesCollapsed) {
        rememberListDetailPaneScaffoldNavigator<Nothing>(
            scaffoldDirective = directive,
            initialDestinationHistory = listOf(
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List),
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail)
            )
        )
    }
    val scope = rememberCoroutineScope()

    // Performs what was asked for, and puts the wall back in front of whoever
    // asked - on a phone the list was covering it, and on a tablet both panes
    // are already up and this does nothing.
    val run = { action: PendingAction ->
        when (action) {
            is PendingAction.Open -> onLoadRoute(action.routeId)
            PendingAction.StartNew -> onNewRoute()
        }
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
        Unit
    }

    // Anything that takes the current work off the wall asks first when there
    // is unsaved work to lose, and otherwise simply happens.
    val start = { action: PendingAction ->
        if ((state as? WallUiState.Connected)?.modified == true) {
            dialog = RouteDialog.UnsavedChanges(action)
        } else {
            run(action)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state is WallUiState.Connected) {
                WallTopBar(
                    modifier = Modifier.onGloballyPositioned { topBarHeight = it.size.height },
                    name = state.name,
                    on = state.on,
                    brightness = state.brightness,
                    enabled = !state.busy,
                    brightnessOpen = brightnessOpen,
                    onBrightnessOpenChange = { brightnessOpen = it },
                    onToggle = onToggle,
                    onBrightnessChange = onBrightnessChange,
                    onChangeController = onChangeController,
                    // A wall that could not be stored has nowhere to put an
                    // imported route, and nothing to export.
                    canBackupRoutes = state.wallId != null,
                    canExportRoutes = routes.isNotEmpty(),
                    onExportRoutes = onExportRoutes,
                    onImportRoutes = onImportRoutes,
                    onToggleRoutes = {
                        // The brightness row floats over the content, so going
                        // to the routes would have left it hanging over the
                        // list. It closes on a press anywhere below the bar
                        // already; the bar itself sits outside that, which is
                        // why this has to say so.
                        brightnessOpen = false

                        if (showsBothPanes) {
                            // Put the list away, or bring it back. Collapsing
                            // also makes the wall the current pane, or the
                            // single pane left would be the list - which is
                            // hiding the wrong half.
                            // The rebuilt navigator opens on the wall, so
                            // collapsing cannot leave the list as the one pane
                            // that is left.
                            routesCollapsed = !routesCollapsed
                        } else {
                            scope.launch {
                                val showingRoutes =
                                    navigator.currentDestination?.pane ==
                                        ListDetailPaneScaffoldRole.List
                                navigator.navigateTo(
                                    if (showingRoutes) {
                                        ListDetailPaneScaffoldRole.Detail
                                    } else {
                                        ListDetailPaneScaffoldRole.List
                                    }
                                )
                            }
                        }
                    },
                )

                // The list beside the editor where there is room and one at a
                // time where there is not, from one implementation - see
                // docs/navigation.md. On a tablet picking a route stops being a
                // navigation event at all, because the list never leaves.
                NavigableListDetailPaneScaffold(
                    navigator = navigator,
                    modifier = Modifier
                        .fillMaxSize()
                        // Closes the brightness row on a press anywhere below
                        // it, the way a menu dismisses. Watched on the initial
                        // pass and never consumed, so the press still reaches
                        // whatever it landed on - tapping a hold both paints it
                        // and puts the row away, rather than being swallowed as
                        // a dismissal and needing a second tap.
                        .pointerInput(brightnessOpen) {
                            if (!brightnessOpen) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press) {
                                        brightnessOpen = false
                                    }
                                }
                            }
                        },
                    listPane = {
                        AnimatedPane {
                            RoutesPanel(
                                routes = routes,
                                selectedRouteId = state.selectedRouteId,
                                currentFingerprint = state.wall.fingerprint,
                                // No stored wall means nothing for a route to
                                // belong to. The save action goes quiet rather
                                // than failing when pressed.
                                canSave = state.wallId != null,
                                onLoad = { routeId ->
                                    start(PendingAction.Open(routeId))
                                },
                                onNew = { start(PendingAction.StartNew) },
                                onRename = { dialog = RouteDialog.Rename(it.id) },
                                onDelete = { dialog = RouteDialog.Delete(it.id) }
                            )
                        }
                    },
                    detailPane = {
                        AnimatedPane {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 8.dp, bottom = 16.dp)
                            ) {
                                // Pinned under the bar rather than carried
                                // along with the grid. The wall is centred in
                                // whatever height is left over, and a title
                                // centred with it drifted down the screen away
                                // from the bar it belongs under.
                                RouteTitle(
                                    routeId = openRoute?.id,
                                    routeName = openRoute?.name,
                                    enabled = !state.busy,
                                    onRename = { newName ->
                                        openRoute?.let { onRenameRoute(it.id, newName) }
                                    }
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    ConnectedContent(
                                        state = state,
                                        routeName = openRoute?.name,
                                        onSave = save,
                                        onSaveAs = {
                                        openRoute?.let { dialog = RouteDialog.NameCopy(it.id) }
                                    },
                                        onReset = { dialog = RouteDialog.Reset },
                                        onHoldTap = onHoldTap,
                                        onColorSelect = onColorSelect,
                                        onClearWall = onClearWall
                                    )
                                }
                            }
                        }
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Exhaustive without an else: the branch above narrows
                    // state to everything that is not Connected.
                    when (state) {
                        is WallUiState.Connecting -> ConnectingContent()
                        is WallUiState.Error ->
                            ErrorContent(problem = state.problem, onRetry = onRetry)
                    }
                    // The top bar and its menu are absent here, so changing the
                    // controller has to stay reachable - it is the way out of an
                    // address that no longer answers.
                    TextButton(
                        onClick = onChangeController,
                        modifier = Modifier.padding(top = 32.dp)
                    ) {
                        Text(text = stringResource(R.string.wall_change_controller))
                    }
                }
            }
        }

        // Drawn over the content, not above it in the layout. Inline, this
        // pushed the grid down while open and let it spring back on close -
        // and since it closes on a tap, aiming at a hold slid the grid up
        // under the finger before the tap resolved, painting the hold below
        // the one intended.
        if (state is WallUiState.Connected && brightnessOpen) {
            BrightnessControl(
                brightness = state.brightness,
                enabled = !state.busy,
                onBrightnessChange = onBrightnessChange,
                modifier = Modifier.offset { IntOffset(0, topBarHeight) }
            )
        }
    }

    if (state is WallUiState.Connected) {
        dialog?.let { open ->
            RouteDialogHost(
                dialog = open,
                routes = routes,
                openRoute = openRoute,
                onDismiss = { dialog = null },
                onShow = { dialog = it },
                onRun = { action ->
                    dialog = null
                    run(action)
                },
                onSaveRoute = onSaveRoute,
                onRenameRoute = onRenameRoute,
                onDeleteRoute = onDeleteRoute,
                onRevertRoute = onRevertRoute
            )
        }
    }
}

@Composable
private fun ColumnScope.ConnectingContent() {
    CircularProgressIndicator()
    Text(
        text = stringResource(R.string.wall_connecting),
        modifier = Modifier.padding(top = 16.dp)
    )
}

/**
 * The open route's name, renameable in place.
 *
 * Tapping it turns it into a field rather than opening a dialog: renaming is
 * a small edit to something already on screen, and a dialog to change one word
 * is heavier than the change. The pencil beside it is what says so - an
 * editable title that looks exactly like a label is a feature nobody finds.
 *
 * Committing on the way out as well as on Done, because a title edited in
 * place is expected to keep what was typed when you look away from it. There
 * is nothing to lose by being wrong: renaming again is the same gesture.
 */

@Composable
private fun ColumnScope.ConnectedContent(
    state: WallUiState.Connected,
    routeName: String?,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onReset: () -> Unit,
    onHoldTap: (segmentIndex: Int) -> Unit,
    onColorSelect: (HoldColor) -> Unit,
    onClearWall: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(MIN_GRID_SCALE) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }

    RouteActions(
        routeName = routeName,
        modified = state.modified,
        enabled = !state.busy,
        canSave = state.wallId != null,
        onSave = onSave,
        onSaveAs = onSaveAs,
        onReset = onReset
    )

    WallGrid(
        wall = state.wall,
        litHolds = state.litHolds,
        onHoldTap = onHoldTap,
        scale = scale,
        pan = pan,
        onTransform = { gesturePan, gestureZoom, gridSize ->
            viewport = gridSize
            scale = clampGridScale(scale * gestureZoom)
            pan = clampGridPan(pan + gridPanDelta(gesturePan, scale), scale, gridSize)
        },
        modifier = Modifier
            .weight(1f, fill = false)
            .padding(top = 8.dp)
    )
    GridControls(
        scale = scale,
        canClear = state.litHolds.isNotEmpty(),
        onClearWall = onClearWall,
        onZoom = { factor ->
            scale = clampGridScale(scale * factor)
            // Zooming back out has to pull the grid back into view, or it
            // would sit off-centre with empty space beside it.
            pan = clampGridPan(pan, scale, viewport)
        },
        modifier = Modifier.padding(top = 8.dp)
    )
    ColorPalette(
        selected = state.selectedColor,
        onSelect = onColorSelect,
        modifier = Modifier.padding(top = 16.dp)
    )
}

@Composable
private fun ColumnScope.ErrorContent(problem: WallProblem, onRetry: () -> Unit) {
    Text(text = stringResource(R.string.wall_error_title))
    Text(
        text = stringResource(
            when (problem) {
                WallProblem.Unreachable -> R.string.wall_error_unreachable
                WallProblem.NotAWledMatrix -> R.string.wall_error_not_wled
                WallProblem.Unidentifiable -> R.string.wall_error_unidentifiable
            }
        ),
        modifier = Modifier.padding(top = 8.dp)
    )
    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
        Text(text = stringResource(R.string.wall_retry))
    }
}

/**
 * The wall's holds, sized so the whole wall is visible at once - reading a
 * route end to end matters more than per-cell precision.
 *
 * The grid owns every gesture itself, taps included. Per-cell `clickable`
 * cannot be used here: children are dispatched pointer events before their
 * parent, and detectTransformGestures abandons the gesture the moment any
 * change is consumed, so clickable cells silently swallowed every pinch and
 * drag. Cells keep an explicit semantics onClick so screen readers can still
 * activate them.
 */

internal const val PRIVACY_POLICY_URL =
    "https://aaronpatterson.github.io/WLED-Climb/privacy.html"
