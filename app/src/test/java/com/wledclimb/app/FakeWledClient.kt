package com.wledclimb.app

import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.network.MAX_BRIGHTNESS
import com.wledclimb.app.network.MIN_USABLE_BRIGHTNESS
import com.wledclimb.app.grid.GapLayout
import com.wledclimb.app.grid.Wall
import com.wledclimb.app.network.gapLayoutFrom
import com.wledclimb.app.network.serializeGaps
import com.wledclimb.app.network.wallFrom
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.network.WledIdentity
import com.wledclimb.app.network.WledStatus

/** A `/json/cfg` body describing one 2x2 panel, enough to build a real Wall from. */
const val TWO_BY_TWO_CONFIG = """
    {"hw":{"led":{"matrix":{"panels":[
        {"b":false,"r":false,"v":false,"s":false,"x":0,"y":0,"h":2,"w":2}
    ]}}}}
"""

/** What a WLED controller running a plain 1D strip looks like: no "matrix" key. */
const val ONE_DIMENSIONAL_CONFIG = """{"hw":{"led":{"total":30}}}"""

/**
 * In-memory [WledClient]. Keeps ViewModel tests deterministic - no sockets, no
 * timeouts, and failures can be injected exactly where they're wanted.
 */
class FakeWledClient(
    var on: Boolean = false,
    var brightness: Int = 128,
    var name: String = "Test wall",
    var mac: String = "b0cbd8e23458",
    var config: String = TWO_BY_TWO_CONFIG,
    var gaps: String? = null,
    /** When set, every call throws this instead of returning. */
    var failWith: Exception? = null
) : WledClient {

    val setOnCalls = mutableListOf<Boolean>()

    /** Every brightness push, as (brightness, on) so the "on" field can be asserted. */
    val setBrightnessCalls = mutableListOf<Pair<Int, Boolean>>()
    var getWallCount = 0
        private set

    override suspend fun getStatus(): WledStatus {
        failWith?.let { throw it }
        return WledStatus(on = on, brightness = brightness)
    }

    override suspend fun setOn(on: Boolean): WledStatus {
        failWith?.let { throw it }
        setOnCalls += on
        this.on = on
        return WledStatus(on = on, brightness = brightness)
    }

    override suspend fun setBrightness(brightness: Int, on: Boolean): WledStatus {
        failWith?.let { throw it }
        setBrightnessCalls += brightness to on
        // Mirrors the real client, which clamps rather than trusting callers.
        this.brightness = brightness.coerceIn(MIN_USABLE_BRIGHTNESS, MAX_BRIGHTNESS)
        return WledStatus(on = on, brightness = this.brightness)
    }

    override suspend fun getIdentity(): WledIdentity {
        failWith?.let { throw it }
        return WledIdentity(name = name, mac = mac)
    }

    override suspend fun getWall(): Wall {
        failWith?.let { throw it }
        getWallCount++
        // Built from the same JSON the fixtures always held, so a test that
        // wants a particular wall still writes it the way WLED would send it.
        return wallFrom(rawConfig = config, rawGaps = gaps)
    }

    override suspend fun getGapLayout(): GapLayout {
        failWith?.let { throw it }
        return gapLayoutFrom(rawConfig = config, rawGaps = gaps)
    }

    /** When set, saving the gap file throws this - and nothing else does. */
    var failSaveWith: Exception? = null

    /** When set, restarting throws this - and nothing else does. */
    var failRestartWith: Exception? = null

    /** Every gap file saved, oldest first. */
    val savedGapLayouts = mutableListOf<GapLayout>()
    var restartCount = 0
        private set

    override suspend fun saveGapLayout(layout: GapLayout) {
        failWith?.let { throw it }
        failSaveWith?.let { throw it }
        savedGapLayouts += layout
        // Written back the way the controller would serve it, so the next
        // read of the wall sees the new shape.
        gaps = serializeGaps(layout)
    }

    override suspend fun restart() {
        failWith?.let { throw it }
        failRestartWith?.let { throw it }
        restartCount++
    }

    /** Every wall state pushed, oldest first. */
    val pushedHolds = mutableListOf<Map<Int, String>>()
    var lastPixelCount: Int? = null
        private set

    override suspend fun setHoldColors(pixelCount: Int, lit: Map<Int, String>) {
        failWith?.let { throw it }
        lastPixelCount = pixelCount
        pushedHolds += lit
    }
}
