package com.wledclimb.app.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R
import com.wledclimb.app.grid.Wall
import com.wledclimb.app.palette.HoldColor

/**
 * The wall itself: the grid of holds, the zoom controls beneath it, and the
 * colour tray beneath those.
 *
 * Split out of WallScreen, which had grown to hold the navigation, the dialogs
 * and this. Nothing here knows which route is loaded or how the screen is
 * arranged - it draws a wall and reports taps.
 */

@Composable
internal fun WallGrid(
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
        pluralStringResource(
                    R.plurals.wall_grid_description,
                    holdCount,
                    holdCount,
                    wall.width,
                    wall.height
                )

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
internal fun GridControls(
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
internal fun ColorPalette(
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
