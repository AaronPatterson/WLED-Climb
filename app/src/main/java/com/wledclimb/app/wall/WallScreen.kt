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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onDeleteRoute: (Long) -> Unit
) {
    var brightnessOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<StoredRoute?>(null) }
    // Held while the "save first?" question is on screen, and run once it is
    // answered. Switching away from unsaved work is the only place the app
    // can silently lose something someone made.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var savingAsNew by remember { mutableStateOf<StoredRoute?>(null) }
    var deleting by remember { mutableStateOf<StoredRoute?>(null) }
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
        if (openRoute == null) saving = true else onSaveRoute(openRoute.name, openRoute.id)
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(
        initialDestinationHistory = listOf(
            ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List),
            ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail)
        )
    )
    val scope = rememberCoroutineScope()

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
                    onToggleRoutes = {
                        // The brightness row floats over the content, so going
                        // to the routes would have left it hanging over the
                        // list. It closes on a press anywhere below the bar
                        // already; the bar itself sits outside that, which is
                        // why this has to say so.
                        brightnessOpen = false
                        scope.launch {
                            // A toggle, not a one-way trip. The same button
                            // that covered the wall with the list puts it back,
                            // so nobody has to know that the system back
                            // gesture is the way out of a screen they opened
                            // from the bar.
                            //
                            // On a tablet the list never leaves, so both sides
                            // of this are the same thing and the button does
                            // nothing visible - which is correct, there being
                            // nothing to close.
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
                                    val load = {
                                        onLoadRoute(routeId)
                                        // Back to the wall on a phone, where
                                        // the list covered it. On a tablet both
                                        // panes are already up and this does
                                        // nothing.
                                        scope.launch {
                                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                                        }
                                        Unit
                                    }
                                    if (state.modified) pending = load else load()
                                },
                                onNew = {
                                    val new = {
                                        onNewRoute()
                                        scope.launch {
                                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                                        }
                                        Unit
                                    }
                                    if (state.modified) pending = new else new()
                                },
                                onRename = { renaming = it },
                                onDelete = { deleting = it }
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
                                    routeName = openRoute?.name,
                                    modified = state.modified,
                                    enabled = !state.busy,
                                    onSave = save,
                                    onSaveAs = { savingAsNew = openRoute },
                                    onReset = { resetting = true }
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

    if (state is WallUiState.Connected && saving) {
        SaveRouteDialog(
            initialName = "",
            onDismiss = {
                saving = false
                afterSave = null
            },
            onSave = { name ->
                saving = false
                onSaveRoute(name, null)
                afterSave?.invoke()
                afterSave = null
            }
        )
    }

    savingAsNew?.let { route ->
        SaveRouteDialog(
            initialName = route.name,
            onDismiss = { savingAsNew = null },
            onSave = { name ->
                savingAsNew = null
                onSaveRoute(name, null)
            }
        )
    }

    if (state is WallUiState.Connected) {
        pending?.let { action ->
            val open = routes.firstOrNull { it.id == state.selectedRouteId }
            UnsavedChangesDialog(
                routeName = open?.name,
                onCancel = { pending = null },
                onDiscard = {
                    pending = null
                    action()
                },
                onSave = {
                    pending = null
                    // Straight to the save dialog, which then runs the action
                    // it interrupted - so answering "save" does not also mean
                    // losing the thing you were trying to open.
                    afterSave = action
                    saving = true
                }
            )
        }
    }

    if (state is WallUiState.Connected && resetting) {
        ResetRouteDialog(
            routeName = routes.firstOrNull { it.id == state.selectedRouteId }?.name,
            onDismiss = { resetting = false },
            onReset = {
                resetting = false
                onRevertRoute()
            }
        )
    }

    renaming?.let { route ->
        RenameRouteDialog(
            initialName = route.name,
            onDismiss = { renaming = null },
            onRename = { name ->
                renaming = null
                onRenameRoute(route.id, name)
            }
        )
    }

    deleting?.let { route ->
        DeleteRouteDialog(
            name = route.name,
            onDismiss = { deleting = null },
            onDelete = {
                deleting = null
                onDeleteRoute(route.id)
            }
        )
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

@Composable
private fun RouteTitle(
    routeName: String?,
    modified: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = routeName ?: stringResource(R.string.routes_unsaved),
            // A step above the wall's name in the bar, which is now titleLarge.
            // The route is the thing being worked on and should stay the
            // larger of the two.
            style = MaterialTheme.typography.headlineSmall,
            color = if (routeName == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Everything that acts on what is on the wall, beside the name of what
        // is on the wall. The routes list keeps only the one action that is
        // about the list itself - starting a new route.
        //
        // No slot is reserved for any of these. The name is weighted, so it
        // starts in the same place whatever appears to its right; only the
        // width it has to truncate into changes.
        if (modified) {
            IconButton(onClick = onReset, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_reset),
                    contentDescription = stringResource(R.string.routes_reset)
                )
            }
        }

        // Copying a route to work from is worth offering before anything has
        // been changed, so this does not wait for edits the way the other two
        // do - only for there being a route to copy.
        if (routeName != null) {
            IconButton(onClick = onSaveAs, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_save_as),
                    contentDescription = stringResource(R.string.routes_save_new)
                )
            }
        }

        if (modified) {
            FilledTonalIconButton(onClick = onSave, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_save),
                    contentDescription = stringResource(R.string.routes_save_current),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ConnectedContent(
    state: WallUiState.Connected,
    onHoldTap: (segmentIndex: Int) -> Unit,
    onColorSelect: (HoldColor) -> Unit,
    onClearWall: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(MIN_GRID_SCALE) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }

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
@Composable
private fun WallGrid(
    wall: Wall,
    litHolds: Map<Int, HoldColor>,
    onHoldTap: (segmentIndex: Int) -> Unit,
    scale: Float,
    pan: Offset,
    onTransform: (pan: Offset, zoom: Float, viewport: Size) -> Unit,
    modifier: Modifier = Modifier
) {
    val holdCount = wall.holdCount
    val description =
        stringResource(R.string.wall_grid_description, holdCount, wall.width, wall.height)

    // Scaling through graphicsLayer doesn't change the layout size, so a
    // zoomed grid would otherwise paint straight over the controls below it.
    BoxWithConstraints(modifier.clipToBounds()) {
        // Fit whichever dimension runs out first: on a landscape tablet a
        // 12x12 wall is limited by height, on a phone by width. Fitting only
        // to width pushes the controls below the grid off the screen.
        val cellSize = if (wall.width > 0 && wall.height > 0) {
            minOf(maxWidth / wall.width, maxHeight / wall.height)
        } else {
            0.dp
        }
        val cellPx = with(LocalDensity.current) { cellSize.toPx() }
        val viewport = Size(cellPx * wall.width, cellPx * wall.height)

        Column(
            modifier = Modifier
                .semantics { contentDescription = description }
                // graphicsLayer rather than re-laying out at a larger cell
                // size, so zooming doesn't re-measure every cell each frame.
                // Pointer input sits inside the layer, so the offsets it
                // reports are already in the grid's own coordinates.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                }
                .pointerInput(cellPx, wall) {
                    detectTapGestures { offset ->
                        val x = (offset.x / cellPx).toInt()
                        val y = (offset.y / cellPx).toInt()
                        if (x in 0 until wall.width &&
                            y in 0 until wall.height &&
                            wall.hasHoldAt(x, y)
                        ) {
                            onHoldTap(wall.segmentIndexAt(x, y))
                        }
                    }
                }
                .pointerInput(viewport) {
                    detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                        onTransform(gesturePan, gestureZoom, viewport)
                    }
                }
        ) {
            for (y in 0 until wall.height) {
                Row {
                    for (x in 0 until wall.width) {
                        // Grid position, not position along the strip: this is
                        // what WLED's per-pixel commands address.
                        val segmentIndex = wall.segmentIndexAt(x, y)
                        HoldCell(
                            segmentIndex = if (wall.hasHoldAt(x, y)) segmentIndex else null,
                            color = litHolds[segmentIndex],
                            column = x,
                            row = y,
                            size = cellSize,
                            onTap = onHoldTap
                        )
                    }
                }
            }
        }
    }
}

/**
 * One cell. A gap (no LED behind it) takes up its space but isn't a control -
 * there's nothing there to light.
 *
 * Taps are handled by the grid, not here; the onClick below exists so screen
 * readers still have something to activate.
 */
@Composable
private fun HoldCell(
    segmentIndex: Int?,
    color: HoldColor?,
    column: Int,
    row: Int,
    size: Dp,
    onTap: (segmentIndex: Int) -> Unit
) {
    val unlitColor = WallStatusColors.gridCell
    val holdDescription = if (color == null) {
        stringResource(R.string.wall_hold_description, column + 1, row + 1)
    } else {
        stringResource(
            R.string.wall_hold_lit_description,
            column + 1,
            row + 1,
            stringResource(color.labelRes)
        )
    }

    Box(
        modifier = Modifier
            .size(size)
            .padding(1.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                when {
                    segmentIndex == null -> Color.Transparent
                    color != null -> color.displayColor
                    else -> unlitColor
                }
            )
            .then(
                if (segmentIndex == null) {
                    // An empty cell isn't a control; don't announce it at all.
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier.semantics {
                        contentDescription = holdDescription
                        onClick {
                            onTap(segmentIndex)
                            true
                        }
                    }
                }
            )
    )
}

/**
 * Zoom steps, for anyone who would rather not pinch, plus clearing the wall.
 *
 * Clearing sits here rather than next to the palette: there's no undo and no
 * saved routes yet, so it's worth keeping away from where fingers are busy
 * painting. It's disabled when there's nothing lit, so it can't wipe by
 * accident when the wall is already clear.
 */
@Composable
private fun GridControls(
    scale: Float,
    canClear: Boolean,
    onClearWall: () -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Icons rather than labels: three words of chrome under the grid read as
        // a sentence to be parsed, and none of them is the point of the screen.
        // The labels survive as content descriptions, so nothing is lost to a
        // screen reader.
        IconButton(onClick = { onZoom(1f / ZOOM_STEP) }, enabled = scale > MIN_GRID_SCALE) {
            Icon(
                painter = painterResource(R.drawable.ic_zoom_out),
                contentDescription = stringResource(R.string.wall_zoom_out)
            )
        }
        IconButton(onClick = { onZoom(ZOOM_STEP) }, enabled = scale < MAX_GRID_SCALE) {
            Icon(
                painter = painterResource(R.drawable.ic_zoom_in),
                contentDescription = stringResource(R.string.wall_zoom_in)
            )
        }
        IconButton(onClick = onClearWall, enabled = canClear) {
            Icon(
                painter = painterResource(R.drawable.ic_clear_wall),
                contentDescription = stringResource(R.string.wall_clear)
            )
        }
    }
}

/**
 * The colours a hold can be painted in. Tapping one arms it for the next tap.
 *
 * Swatches size themselves to the width available rather than taking a fixed
 * size: six at 56dp don't fit across a phone, and a Row that runs out of room
 * squashes the last swatch that fits and drops the rest off the edge entirely.
 * They never grow past 56dp, and at phone width land on 48dp - the smallest a
 * touch target should be, and these get tapped by six-year-olds.
 */
@Composable
private fun ColorPalette(
    selected: HoldColor,
    onSelect: (HoldColor) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = HoldColor.entries
    val spacing = 8.dp

    BoxWithConstraints(modifier) {
        val swatchSize = minOf(
            MAX_SWATCH_SIZE,
            (maxWidth - spacing * (colors.size - 1)) / colors.size
        )

        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            for (color in colors) {
                val isSelected = color == selected
                val label = stringResource(color.labelRes)
                val swatchDescription =
                    if (isSelected) stringResource(R.string.color_selected, label) else label
                Box(
                    modifier = Modifier
                        .size(swatchSize)
                        .clip(CircleShape)
                        .background(color.displayColor)
                        .border(
                            width = if (isSelected) 4.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                        .clickable { onSelect(color) }
                        .semantics { contentDescription = swatchDescription }
                )
            }
        }
    }
}

/** Big enough to tap easily; past this they just look oversized on a tablet. */
private val MAX_SWATCH_SIZE = 56.dp

/** WLED's "RRGGBB" as an opaque Compose colour. */
/**
 * Served from the repository's own GitHub Pages site, so it costs nothing and
 * cannot lapse. Changing what it says needs no store review; changing this
 * address does, since Play holds it as part of the listing.
 */
internal const val PRIVACY_POLICY_URL =
    "https://aaronpatterson.github.io/WLED-Climbing-Wall/privacy.html"

private val HoldColor.displayColor: Color
    get() = Color(hex.toLong(16) or 0xFF000000L)

private val HoldColor.labelRes: Int
    get() = when (this) {
        HoldColor.Red -> R.string.color_red
        HoldColor.Orange -> R.string.color_orange
        HoldColor.Yellow -> R.string.color_yellow
        HoldColor.Green -> R.string.color_green
        HoldColor.Blue -> R.string.color_blue
        HoldColor.Purple -> R.string.color_purple
    }
