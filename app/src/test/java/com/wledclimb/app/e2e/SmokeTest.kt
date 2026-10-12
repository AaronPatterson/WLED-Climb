package com.wledclimb.app.e2e

import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest : EndToEndTest() {

    @Test
    fun `a fresh install connects and lights a hold`() {
        launchConnected()

        tapHold(2, 2)

        assertLit(2, 2, "Red", "hold should show lit")
        eventually { controller.showing.size == 1 }
    }
}
