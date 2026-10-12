package com.wledclimb.app.e2e

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Working without the controller: because it is out of reach, or because
 * someone chose to so as not to disturb the route on the wall.
 *
 * The power button is the witness throughout. It is named for what it will do
 * next, so its description is the app's own statement of what it believes
 * about the wall.
 */
class OfflineTest : EndToEndTest() {

    private val outOfReach = "Wall out of reach. Try again"
    private val workingOffline = "Working offline. Go back online"

    private fun saveNew(name: String) {
        tapSave()
        nameAndSave(name)
        assertTitle(name)
    }

    private fun power() = compose.onNode(hasContentDescription(powerDescription()!!))

    /** A wall with Warmup (1,1) saved, then reopened with the controller unplugged. */
    private fun knownWallOutOfReach() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        controller.reachable = false
        relaunch()
        waitForWall(controller.name)
        waitForPower(outOfReach)
    }

    /** Connected, with (1,1) on the wall, then told to work offline. */
    private fun workingOfflineWithOneHoldSent() {
        launchConnected()
        tapHold(1, 1)
        eventually { controller.showing.keys == setOf(segment(1, 1)) }
        openMenu()
        menuItem("Work offline").performClick()
        waitForPower(workingOffline)
    }

    // --- connected --------------------------------------------------------

    @Test
    fun `power switches the wall off and on`() {
        launchConnected()
        waitForPower("Turn wall off")

        power().performClick()
        eventually { !controller.on }
        waitForPower("Turn wall on")

        power().performClick()
        eventually { controller.on }
        waitForPower("Turn wall off")
    }

    // --- out of reach -----------------------------------------------------

    @Test
    fun `a known wall opens from storage when its controller is out of reach`() {
        knownWallOutOfReach()

        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        // Nothing to say about brightness without a controller to ask.
        compose.onNode(hasContentDescription("Brightness")).assertIsNotEnabled()
    }

    @Test
    fun `routes can be edited and saved while out of reach`() {
        knownWallOutOfReach()

        tapHold(2, 2)
        tapSaveAsNew()
        waitForText("Save as a new route")
        typeInDialog("Warmup 2")
        dialogButton("Save").performClick()

        assertTitle("Warmup 2")
        openRoutes()
        assertEquals(setOf("Warmup", "Warmup 2"), savedRouteNames().toSet())
    }

    @Test
    fun `once the controller is back, tapping power reconnects and sends the wall`() {
        knownWallOutOfReach()
        tapHold(2, 2)

        controller.reachable = true
        power().performClick()

        waitForPower("Turn wall off")
        eventually { controller.showing.keys == setOf(segment(1, 1), segment(2, 2)) }
    }

    @Test
    fun `working offline is not offered while the wall is out of reach`() {
        // Already not talking to anything, and the app never retries on its
        // own, so the choice would change nothing - greyed and unchecked.
        knownWallOutOfReach()

        openMenu()

        menuItem("Work offline").assertIsNotEnabled()
        menuItem("Work offline").assertIsOff()
    }

    @Test
    fun `a long press while out of reach does not work offline`() {
        knownWallOutOfReach()

        power().performTouchInput { longClick() }

        compose.waitForIdle()
        assertNotEquals(workingOffline, powerDescription())
    }

    // --- working offline by choice ----------------------------------------

    @Test
    fun `working offline leaves the wall as it is`() {
        workingOfflineWithOneHoldSent()
        val sent = controller.pushes.size

        tapHold(3, 3)

        assertLit(3, 3, "Red")
        assertEquals("nothing should be sent while offline", sent, controller.pushes.size)
        assertEquals(setOf(segment(1, 1)), controller.showing.keys)
    }

    @Test
    fun `work offline is checked in the menu while working offline`() {
        workingOfflineWithOneHoldSent()

        openMenu()

        menuItem("Work offline").assertIsOn()
    }

    @Test
    fun `going back online sends what is on screen`() {
        // Coming back is taking the wall back: what this device shows, the
        // wall shows.
        workingOfflineWithOneHoldSent()
        tapHold(3, 3)

        power().performClick()

        waitForPower("Turn wall off")
        eventually { controller.showing.keys == setOf(segment(1, 1), segment(3, 3)) }
    }

    @Test
    fun `unchecking work offline in the menu also goes back online`() {
        workingOfflineWithOneHoldSent()

        openMenu()
        menuItem("Work offline").performClick()

        waitForPower("Turn wall off")
    }

    @Test
    fun `a long press on power works offline`() {
        // The grown-up's gesture: the menu item exists so it can be found,
        // and this is the quick way.
        launchConnected()
        waitForPower("Turn wall off")

        power().performTouchInput { longClick() }

        waitForPower(workingOffline)
    }

    @Test
    fun `working offline survives the app being closed`() {
        // A tablet parked offline so someone could build a route must not
        // grab the wall back the moment the app is reopened.
        workingOfflineWithOneHoldSent()
        val sent = controller.pushes.size

        relaunch()

        waitForWall(controller.name)
        waitForPower(workingOffline)
        tapHold(3, 3)
        assertLit(3, 3, "Red")
        assertEquals("nothing should be sent", sent, controller.pushes.size)
    }
}
