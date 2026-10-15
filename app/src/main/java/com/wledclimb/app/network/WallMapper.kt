package com.wledclimb.app.network

import com.wledclimb.app.grid.GapCell
import com.wledclimb.app.grid.GapLayout
import com.wledclimb.app.grid.Wall

/**
 * Builds a [Wall] from the controller's panel layout and optional gap file.
 *
 * A cell holds a light when a panel covers it **and** the gap file does not
 * exclude it. Both halves matter: a matrix made of panels that do not fill its
 * bounding box has cells no panel reaches, and those are empty regardless of
 * what the gap file says.
 *
 * [gaps] is WLED's optional `/2d-gaps.json`: one entry per cell in the
 * matrix's bounding box, row-major. -1 means no LED is physically there and 0
 * means an LED is wired there but unusable. The app treats both as "no hold",
 * because the difference between them is only about how LED indices shift
 * along the strip - which matters to WLED's own rendering and not to anything
 * sent from here. A [gaps] list shorter than the matrix's cell count is
 * ignored entirely, matching WLED's own fallback.
 *
 * This used to port WLED's `WS2812FX::setUpMatrix()` in full, walking each
 * panel in wiring order to assign strip indices. Those indices turned out to
 * be read by nothing: per-pixel commands address grid positions. The walk, and
 * the eight tests pinning its serpentine and orientation behaviour, went with
 * them. [Panel]'s wiring flags are still parsed, because they describe the
 * controller's configuration faithfully. The gap editor turned out not to need
 * them: it works on grid positions too, through [buildGapLayout].
 */
fun buildWall(panels: List<Panel>, gaps: List<Int>? = null): Wall =
    buildGapLayout(panels, gaps).toWall()

/**
 * The same reading of panels and gap file as [buildWall], stopping short of
 * flattening it: each covered position keeps which of WLED's three gap values
 * it has, which is what the gap editor needs and the rest of the app does not.
 *
 * A [gaps] list too short to use reads as every covered position having a
 * hold, which is what WLED does with it - so saving from here replaces a
 * broken file with one that says what the wall is already doing.
 */
fun buildGapLayout(panels: List<Panel>, gaps: List<Int>? = null): GapLayout {
    val width = panels.maxOfOrNull { it.xOffset + it.width } ?: 0
    val height = panels.maxOfOrNull { it.yOffset + it.height } ?: 0

    val effectiveGaps = gaps?.takeIf { it.size >= width * height }

    val cells = MutableList(height) { MutableList<GapCell?>(width) { null } }

    for (panel in panels) {
        for (y in panel.yOffset until panel.yOffset + panel.height) {
            for (x in panel.xOffset until panel.xOffset + panel.width) {
                if (y !in 0 until height || x !in 0 until width) continue
                cells[y][x] = gapCellFor(effectiveGaps?.get(y * width + x))
            }
        }
    }

    return GapLayout(width = width, height = height, cells = cells)
}

/**
 * WLED's reading of one gap value: above zero is lit, zero is an LED left
 * unused, and below zero is no LED - `gapTable[index] > 0` and `>= 0` in its
 * `setUpMatrix()`. No value at all is a position with no gap file to say
 * otherwise, which WLED lights.
 */
private fun gapCellFor(value: Int?): GapCell = when {
    value == null || value > 0 -> GapCell.Hold
    value == 0 -> GapCell.Unused
    else -> GapCell.NoLed
}
