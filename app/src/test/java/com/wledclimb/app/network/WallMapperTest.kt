package com.wledclimb.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallMapperTest {

    private fun panel(
        xOffset: Int = 0,
        yOffset: Int = 0,
        width: Int,
        height: Int,
        bottomStart: Boolean = false,
        rightStart: Boolean = false,
        vertical: Boolean = false,
        serpentine: Boolean = false
    ) = Panel(xOffset, yOffset, width, height, bottomStart, rightStart, vertical, serpentine)

    @Test
    fun `a single panel fills its own bounding box`() {
        val wall = buildWall(listOf(panel(width = 2, height = 2)))

        assertEquals(2, wall.width)
        assertEquals(2, wall.height)
        assertEquals(4, wall.holdCount)
    }

    @Test
    fun `two panels side by side match a real controller's config`() {
        // From an actual WLED controller's /json/cfg: two 6x12 panels side by
        // side, both wired in vertical, bottom-started, serpentine columns.
        val left = panel(
            width = 6, height = 12,
            bottomStart = true, vertical = true, serpentine = true
        )
        val wall = buildWall(listOf(left, left.copy(xOffset = 6)))

        assertEquals(12, wall.width)
        assertEquals(12, wall.height)
        assertEquals(144, wall.holdCount)
    }

    @Test
    fun `wiring flags no longer change the result`() {
        // The whole serpentine and orientation walk existed to assign strip
        // indices. Now that those are gone, two panels of the same size and
        // position must produce identical walls however they are wired - this
        // is the regression test for that contract.
        val plain = panel(width = 4, height = 3)
        val tangled = plain.copy(
            bottomStart = true, rightStart = true, vertical = true, serpentine = true
        )

        assertEquals(buildWall(listOf(plain)), buildWall(listOf(tangled)))
    }

    @Test
    fun `no panels produces an empty wall`() {
        val wall = buildWall(emptyList())

        assertEquals(0, wall.width)
        assertEquals(0, wall.height)
        assertEquals(0, wall.holdCount)
    }

    @Test
    fun `cells not covered by any panel are empty`() {
        // Two panels diagonally offset leave the other two quadrants uncovered.
        val wall = buildWall(
            listOf(
                panel(width = 2, height = 2),
                panel(xOffset = 2, yOffset = 2, width = 2, height = 2)
            )
        )

        assertEquals(4, wall.width)
        assertEquals(4, wall.height)
        assertTrue(wall.hasHoldAt(x = 0, y = 0))
        assertTrue(wall.hasHoldAt(x = 3, y = 3))
        assertFalse("uncovered quadrant", wall.hasHoldAt(x = 3, y = 0))
        assertFalse("uncovered quadrant", wall.hasHoldAt(x = 0, y = 3))
        assertEquals(8, wall.holdCount)
    }

    @Test
    fun `a gap of -1 leaves no hold`() {
        val gaps = listOf(1, -1, 1, 1)

        val wall = buildWall(listOf(panel(width = 2, height = 2)), gaps)

        assertTrue(wall.hasHoldAt(x = 0, y = 0))
        assertFalse(wall.hasHoldAt(x = 1, y = 0))
        assertEquals(3, wall.holdCount)
    }

    @Test
    fun `a gap of 0 also leaves no hold`() {
        // -1 and 0 differ in WLED only by whether the LED index shifts for
        // everything after it. The app never computes those indices, so from
        // here the two are the same answer: nothing to light.
        val gaps = listOf(1, 0, 1, 1)

        val wall = buildWall(listOf(panel(width = 2, height = 2)), gaps)

        assertFalse(wall.hasHoldAt(x = 1, y = 0))
        assertEquals(3, wall.holdCount)
    }

    @Test
    fun `a gap list shorter than the matrix is ignored entirely`() {
        // Matches WLED's own fallback rather than applying a partial file.
        val wall = buildWall(listOf(panel(width = 2, height = 2)), listOf(-1, -1))

        assertEquals(4, wall.holdCount)
    }

    @Test
    fun `gaps are read row-major across the whole matrix, not per panel`() {
        // Two 2x2 panels side by side: index 2 in a row-major 4x2 matrix is the
        // first cell of the second panel, not the third cell of the first.
        val gaps = listOf(1, 1, -1, 1, 1, 1, 1, 1)

        val wall = buildWall(
            listOf(panel(width = 2, height = 2), panel(xOffset = 2, width = 2, height = 2)),
            gaps
        )

        assertTrue(wall.hasHoldAt(x = 1, y = 0))
        assertFalse(wall.hasHoldAt(x = 2, y = 0))
        assertEquals(7, wall.holdCount)
    }

    @Test
    fun `segment index is the grid position`() {
        val wall = buildWall(listOf(panel(width = 3, height = 2)))

        assertEquals(0, wall.segmentIndexAt(x = 0, y = 0))
        assertEquals(2, wall.segmentIndexAt(x = 2, y = 0))
        assertEquals(3, wall.segmentIndexAt(x = 0, y = 1))
        assertEquals(6, wall.segmentSize)
    }

    @Test
    fun `hasHoldAt is false outside the grid rather than throwing`() {
        val wall = buildWall(listOf(panel(width = 2, height = 2)))

        assertFalse(wall.hasHoldAt(x = 2, y = 0))
        assertFalse(wall.hasHoldAt(x = 0, y = 2))
        assertFalse(wall.hasHoldAt(x = -1, y = 0))
    }
}
