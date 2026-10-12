package com.wledclimb.app.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Getting from a fresh install to a wall, and back again to change it. */
class SetupTest : EndToEndTest() {

    private val setupTitle = "Connect to your WLED controller"

    private fun addressInField(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    @Test
    fun `a fresh install starts at setup, offering the practice wall and no way back`() {
        launch()

        waitForText(setupTitle)
        assertTrue(exists(hasText("No wall to connect to?")))
        assertTrue(exists(hasText("Try a practice wall")))
        // Nothing behind this screen to go back to on a first run.
        assertFalse(exists(hasText("Back to the wall")))
    }

    @Test
    fun `an empty address is refused with a reason`() {
        launch()
        waitForText(setupTitle)

        tapTestAndSave()

        waitForText("Enter the controller's IP address or hostname")
    }

    @Test
    fun `an address where nothing answers is refused, and setup stays put`() {
        controller.reachable = false
        launch()
        waitForText(setupTitle)

        typeAddress(controller.address)
        tapTestAndSave()

        waitFor(hasText("Couldn't reach", substring = true))
        assertTrue(exists(hasText(setupTitle)))
    }

    @Test
    fun `a device that is not a WLED matrix is refused with a different reason`() {
        // Answering is not enough: a plain LED strip has no grid to draw.
        controller.config = FakeWledController.ONE_DIMENSIONAL
        launch()
        waitForText(setupTitle)

        typeAddress(controller.address)
        tapTestAndSave()

        waitFor(hasText("isn't a WLED controller with a 2D matrix", substring = true))
    }

    @Test
    fun `a controller that answers opens its wall`() {
        launchConnected()

        assertEquals(9, holdCount())
        assertTitle("Unsaved route")
    }

    @Test
    fun `a gap in the wall is not a hold`() {
        // The middle of the wall has no light behind it, so there is nothing
        // there to tap - and nothing for a screen reader to announce.
        controller.gaps = FakeWledController.CENTRE_GAP
        launchConnected()

        assertEquals(8, holdCount())
        assertFalse(exists(hold(2, 2)))
    }

    @Test
    fun `the address is remembered, so the next launch goes straight to the wall`() {
        launchConnected()

        relaunch()

        waitForWall(controller.name)
        assertFalse(exists(hasText(setupTitle)))
    }

    @Test
    fun `changing controller pre-fills the address and can be backed out of`() {
        launchConnected()

        openMenu()
        menuItem("Change controller").performClick()
        waitForText(setupTitle)

        assertEquals(controller.address, addressInField())
        compose.onNode(hasText("Back to the wall")).performClick()
        waitForWall(controller.name)
    }

    @Test
    fun `the system back gesture also leaves setup for the wall`() {
        // Setup was once a one-way door: reachable from a menu, with no way
        // out but to save.
        launchConnected()
        openMenu()
        menuItem("Change controller").performClick()
        waitForText(setupTitle)

        pressBack()

        waitForWall(controller.name)
    }

    @Test
    fun `pointing at a different controller opens that wall instead`() {
        launchConnected()
        val other = FakeWledController().apply {
            name = "Garage Wall"
            mac = "aabbccddeeff"
        }
        try {
            openMenu()
            menuItem("Change controller").performClick()
            waitForText(setupTitle)

            typeAddress(other.address)
            tapTestAndSave()

            waitForWall("Garage Wall")
        } finally {
            other.close()
        }
    }
}
