package com.wledclimb.app.network

import com.wledclimb.app.grid.Wall

import com.wledclimb.app.palette.HoldColor

/**
 * Talks to a WLED controller's JSON HTTP API.
 *
 * An interface so ViewModels depending on it can be tested against a fake
 * instead of real sockets; [HttpWledClient] is the real implementation.
 */
interface WledClient {

    /** Power and brightness, read together because they arrive together. */
    suspend fun getStatus(): WledStatus

    /** Turns the whole wall on or off, returning the state WLED confirms. */
    suspend fun setOn(on: Boolean): WledStatus

    /**
     * Sets master brightness, returning the state WLED confirms.
     *
     * [on] has to be passed and sent explicitly. WLED derives power from
     * brightness when the field is absent - `bool on = root["on"] | (bri > 0)`
     * - so a bare brightness change would switch a sleeping wall back on.
     *
     * [brightness] must not be zero. Brightness crossing up from zero is what
     * triggers WLED's "unfreeze all segments" path, which drops the route from
     * the wall while the app still shows it. Callers clamp to
     * [MIN_USABLE_BRIGHTNESS] rather than relying on the UI never producing a
     * zero.
     */
    suspend fun setBrightness(brightness: Int, on: Boolean): WledStatus

    /**
     * The controller's name and hardware id, both from `/json/info`.
     *
     * Read together because they arrive in the same response, and because the
     * name alone cannot identify a wall - it is free text someone can change.
     */
    suspend fun getIdentity(): WledIdentity

    /**
     * The wall's shape, from `/json/cfg` and the optional `/2d-gaps.json`.
     *
     * Returns a [Wall] rather than the JSON the two came in. These used to
     * hand back raw bodies and leave the caller to make sense of them, which
     * put WLED's config format in every ViewModel that connects and left the
     * layer whose whole job is talking to the controller doing half of it.
     *
     * A gap file is optional - WLED serves one only if it was uploaded
     * through its own 2D setup - and its absence is the normal case rather
     * than an error.
     */
    suspend fun getWall(): Wall

    /**
     * Lights individual holds. [lit] maps a **segment buffer index**
     * (`x + y * width`, see Wall.segmentIndexAt) to an `RRGGBB` colour, and
     * every other position below [pixelCount] is turned off.
     *
     * These are grid positions, not positions along the LED strip: WLED's "i"
     * command writes into the segment's 2D buffer and applies the ledmap
     * itself when rendering.
     *
     * Sends the whole desired state rather than just what changed, so the wall
     * can't drift out of sync with the app - WLED keeps previously set pixels
     * when it receives further updates, so an incremental message would leave
     * a hold lit after it had been cleared in the app.
     */
    suspend fun setHoldColors(pixelCount: Int, lit: Map<Int, String>)
}

/**
 * The lowest brightness the app will send.
 *
 * Not zero, and not merely for taste. WLED unfreezes every segment when
 * brightness rises from zero (`if (bri && !onBefore)` in json.cpp), which
 * silently drops the route the wall is displaying. Keeping brightness off that
 * boundary means the route survives the slider, and leaves "off" as the one
 * thing that turns the wall off - one concept rather than two.
 */
const val MIN_USABLE_BRIGHTNESS = 8

/** WLED's maximum master brightness. */
const val MAX_BRIGHTNESS = 255
