package com.wledclimb.app.network

import com.wledclimb.app.grid.GapCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GapLayoutTest {

    private fun panel(xOffset: Int = 0, yOffset: Int = 0, width: Int, height: Int) =
        Panel(xOffset, yOffset, width, height, false, false, false, false)

    @Test
    fun `an unused LED and no LED are kept apart`() {
        // The whole reason this exists alongside Wall: the two are both "no
        // hold" there, and writing one where the other was shifts every LED
        // after it along the strip.
        val layout = buildGapLayout(listOf(panel(width = 2, height = 2)), listOf(1, 0, -1, 1))

        assertEquals(GapCell.Hold, layout.cellAt(0, 0))
        assertEquals(GapCell.Unused, layout.cellAt(1, 0))
        assertEquals(GapCell.NoLed, layout.cellAt(0, 1))
        assertEquals(GapCell.Hold, layout.cellAt(1, 1))
    }

    @Test
    fun `with no gap file every covered position is a hold`() {
        val layout = buildGapLayout(listOf(panel(width = 2, height = 1)))

        assertEquals(listOf(listOf(GapCell.Hold, GapCell.Hold)), layout.cells)
    }

    @Test
    fun `positions no panel covers are not part of the gap file`() {
        // Two 1x1 panels on a diagonal leave the other two positions of the
        // 2x2 bounding box with nothing behind them, whatever the file says.
        val layout = buildGapLayout(
            listOf(panel(width = 1, height = 1), panel(xOffset = 1, yOffset = 1, width = 1, height = 1)),
            listOf(1, 1, 1, 1)
        )

        assertNull(layout.cellAt(1, 0))
        assertNull(layout.cellAt(0, 1))
        assertEquals(GapCell.Hold, layout.cellAt(0, 0))
    }

    @Test
    fun `an uncovered position cannot be painted`() {
        val layout = buildGapLayout(
            listOf(panel(width = 1, height = 1), panel(xOffset = 1, yOffset = 1, width = 1, height = 1))
        )

        assertEquals(layout, layout.with(1, 0, GapCell.Hold))
    }

    @Test
    fun `painting changes only the position painted`() {
        val layout = buildGapLayout(listOf(panel(width = 2, height = 2)))

        val painted = layout.with(1, 0, GapCell.NoLed)

        assertEquals(
            listOf(
                listOf(GapCell.Hold, GapCell.NoLed),
                listOf(GapCell.Hold, GapCell.Hold)
            ),
            painted.cells
        )
    }

    @Test
    fun `the gap file is written in WLED's own values, row-major`() {
        val layout = buildGapLayout(listOf(panel(width = 2, height = 2)))
            .with(1, 0, GapCell.Unused)
            .with(0, 1, GapCell.NoLed)

        assertEquals(listOf(1, 0, -1, 1), parseGaps(serializeGaps(layout)))
    }

    @Test
    fun `uncovered positions are written as no LED`() {
        val layout = buildGapLayout(
            listOf(panel(width = 1, height = 1), panel(xOffset = 1, yOffset = 1, width = 1, height = 1))
        )

        assertEquals(listOf(1, -1, -1, 1), parseGaps(serializeGaps(layout)))
    }

    @Test
    fun `a saved layout reads back as itself`() {
        val config = """{"hw":{"led":{"matrix":{"panels":[
            {"b":false,"r":false,"v":false,"s":false,"x":0,"y":0,"h":2,"w":3}
        ]}}}}"""
        val original = gapLayoutFrom(config, "[1,0,-1,-1,1,0]")

        assertEquals(original, gapLayoutFrom(config, serializeGaps(original)))
    }
}
