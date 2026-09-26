package com.wledclimb.app.network

/**
 * One physical WLED panel within the overall LED matrix, as configured on the
 * controller's `/json/cfg` (`hw.led.matrix.panels`). Field names match WLED's
 * own (`xOffset`/`x`, `bottomStart`/`b`, etc. - see wled00/cfg.cpp).
 */
data class Panel(
    val xOffset: Int,
    val yOffset: Int,
    val width: Int,
    val height: Int,
    val bottomStart: Boolean,
    val rightStart: Boolean,
    val vertical: Boolean,
    val serpentine: Boolean
)
