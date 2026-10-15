package com.wledclimb.app.grid

/**
 * What the controller's gap file says about one grid position that a panel
 * covers.
 *
 * Three values rather than a yes or no, because WLED's two kinds of "no hold"
 * are not interchangeable. [NoLed] is a position the strip skips entirely, and
 * [Unused] is an LED that is wired in but not lit - which still takes up a
 * place along the strip. Writing one where the other belongs moves every LED
 * after it, so an editor that collapsed them would scramble the wall the first
 * time it saved.
 */
enum class GapCell {

    /** An LED with a hold in front of it: something to light. */
    Hold,

    /** An LED that is wired in but not used - no hold in front of it. */
    Unused,

    /** No LED at all. The strip goes straight past this position. */
    NoLed
}

/**
 * The wall as its gap file describes it, before that is flattened into a
 * [Wall].
 *
 * `cells[y][x]` is null where no panel covers the position. Those cells have
 * no LED whatever the gap file says, so they are not something an editor can
 * change - they are drawn as nothing and left out of every edit.
 *
 * Kept apart from [Wall] deliberately. A wall is what the rest of the app
 * needs - where a hold can be lit - and is what gets stored; this is only
 * meaningful next to the controller it came from, since the distinction
 * between [GapCell.Unused] and [GapCell.NoLed] is not stored anywhere (see
 * docs/walls-and-routes.md). It is read fresh each time the gap file is
 * edited, and never kept.
 */
data class GapLayout(
    val width: Int,
    val height: Int,
    val cells: List<List<GapCell?>>
) {
    /** The position's gap value, or null when no panel covers it. */
    fun cellAt(x: Int, y: Int): GapCell? = cells.getOrNull(y)?.getOrNull(x)

    /**
     * This layout with one position changed. Positions no panel covers stay
     * as they are, since there is nothing there to change.
     */
    fun with(x: Int, y: Int, cell: GapCell): GapLayout {
        if (cellAt(x, y) == null) return this
        return copy(
            cells = cells.mapIndexed { row, values ->
                if (row != y) values else values.mapIndexed { column, value ->
                    if (column == x) cell else value
                }
            }
        )
    }

    /** The wall this layout describes: a hold wherever the gap file says so. */
    fun toWall(): Wall = Wall(
        width = width,
        height = height,
        cells = cells.map { row -> row.map { it == GapCell.Hold } }
    )
}
