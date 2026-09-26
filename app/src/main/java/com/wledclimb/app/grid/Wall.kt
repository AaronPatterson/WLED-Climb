package com.wledclimb.app.grid

/**
 * The climbing wall's grid: [width] x [height] cells, where each either has a
 * hold that can be lit or does not. `cells[y][x]` is true where a hold is.
 *
 * This deliberately does **not** record where each LED sits along the physical
 * strip. WLED's per-pixel `"i"` command addresses the *grid* position
 * (`x + y * width`) and applies its own ledmap when rendering, so the strip
 * order never reaches anything the app sends. Tracking it meant porting WLED's
 * serpentine and panel-orientation walk to produce numbers nothing read - see
 * the git history of the network layer's WallMapper if it is ever needed
 * again.
 */
data class Wall(
    val width: Int,
    val height: Int,
    val cells: List<List<Boolean>>
) {
    /** True where a hold can actually be lit. */
    fun hasHoldAt(x: Int, y: Int): Boolean = cells.getOrNull(y)?.getOrNull(x) == true

    /** Position in WLED's 2D segment buffer - what per-pixel commands address. */
    fun segmentIndexAt(x: Int, y: Int): Int = x + y * width

    /**
     * The grid position a segment index refers to - the inverse of
     * [segmentIndexAt], and only meaningful against the same [width].
     *
     * Saved routes are stored as positions rather than indices for exactly
     * that reason (see [GridPosition]), so this is the boundary where the
     * wire's indices become coordinates and back.
     */
    fun positionOf(segmentIndex: Int): GridPosition =
        GridPosition(x = segmentIndex % width, y = segmentIndex / width)

    /** Size of that buffer, i.e. the range a "clear the whole wall" must cover. */
    val segmentSize: Int get() = width * height

    /** How many cells can be lit, for descriptions and sanity checks. */
    val holdCount: Int get() = cells.sumOf { row -> row.count { it } }
}
