package com.wledclimb.app.storage

/**
 * A wall with no controller behind it, for trying the app without one.
 *
 * Exists because until now the app was unusable without hardware on the same
 * network. That is a problem for three different people: whoever reviews the
 * app for the store and has no climbing wall to point it at, anyone deciding
 * whether the app is worth setting up, and a child whose device never shares a
 * network with the garage. All three see the same thing otherwise - a screen
 * saying the controller cannot be reached, which reads as broken rather than
 * as waiting.
 *
 * Modelled on a real wall rather than invented: 12x12 with 65 holds in the
 * pattern of the wall this app was built for. A grid where every cell is a
 * hold would be a worse demonstration, because it would not show that a wall
 * has gaps - and gaps are most of what makes the grid interesting.
 *
 * Stored as an ordinary wall row, which is what makes this cheap. Routes,
 * drafts, saving, renaming, export and import all work on it unchanged,
 * because none of them ever needed a controller - they work in grid positions
 * and palette slots. See [RouteHolds].
 */
object DemoWall {

    /**
     * Reserved, and not a MAC any controller reports: WLED reads its own from
     * the chip's eFuse, and all-zero is the address that means "no device".
     * Being a MAC at all is what matters - walls are keyed by one, so the
     * practice wall gets a row like any other rather than a special case
     * threaded through storage.
     */
    const val MAC = "000000000000"

    /** Named so it cannot be mistaken for the wall in the garage. */
    const val NAME = "Practice Wall"

    const val WIDTH = 12

    const val HEIGHT = 12

    /** Row-major, '1' where a hold can be lit - see [StoredWall.holdGrid]. */
    const val HOLD_GRID =
        "100101010101" +
        "101010010010" +
        "010101101001" +
        "101010100101" +
        "010100110010" +
        "001001001100" +
        "100010110101" +
        "001010010100" +
        "010101010010" +
        "100100101001" +
        "011011010010" +
        "100110111001"
}
