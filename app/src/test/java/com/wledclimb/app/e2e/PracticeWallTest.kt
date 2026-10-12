package com.wledclimb.app.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The wall with no controller behind it, for trying the app without one. */
class PracticeWallTest : EndToEndTest() {

    private val setupTitle = "Connect to your WLED controller"

    private fun openPracticeWall() {
        waitForText("Try a practice wall")
        compose.onNode(hasText("Try a practice wall")).performClick()
        waitForWall("Practice Wall")
    }

    private fun changeController() {
        openMenu()
        menuItem("Change controller").performClick()
        waitForText(setupTitle)
    }

    private fun addressInField(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    @Test
    fun `it opens with nothing to connect to, shaped like the real wall`() {
        launch()

        openPracticeWall()

        // 12x12 with 65 holds - the wall this app was built for, gaps and all.
        assertEquals(65, holdCount())
    }

    @Test
    fun `power and brightness are absent rather than disabled`() {
        launch()

        openPracticeWall()

        assertNull(powerDescription())
        assertFalse(exists(hasContentDescription("Brightness")))
    }

    @Test
    fun `routes can be built and saved on it`() {
        launch()
        openPracticeWall()

        tapHold(1, 1)
        tapSave()
        nameAndSave("Practice route")

        assertTitle("Practice route")
        openRoutes()
        assertEquals(listOf("Practice route"), savedRouteNames())
    }

    @Test
    fun `working offline is not offered on it`() {
        // There is no controller to leave alone, so the choice is unavailable
        // and unchecked - the checkbox and its row both say so.
        launch()
        openPracticeWall()

        openMenu()

        menuItem("Work offline").assertIsNotEnabled()
        menuItem("Work offline").assertIsOff()
    }

    @Test
    fun `leaving it for setup offers the address already saved`() {
        // Setup opened with an empty box from here, so getting back to the
        // real wall meant typing its address out from memory.
        launchConnected()
        changeController()
        openPracticeWall()

        changeController()

        assertEquals(controller.address, addressInField())
    }

    @Test
    fun `setup opened from it can be left again, back to it`() {
        // With nothing to pre-fill, setup had no way back either: no button
        // and no back gesture, so the only way out was to connect.
        launch()
        openPracticeWall()
        changeController()

        assertEquals("", addressInField())
        compose.onNode(hasText("Back to the wall")).performClick()

        waitForWall("Practice Wall")
    }

    @Test
    fun `the back gesture from setup also returns to it`() {
        launch()
        openPracticeWall()
        changeController()

        pressBack()

        waitForWall("Practice Wall")
    }

    @Test
    fun `it is not what opens next time if a real wall was set up`() {
        // Not recorded as the wall this device last reached, which would open
        // it next launch in place of the garage.
        launchConnected()
        changeController()
        openPracticeWall()

        relaunch()

        waitForWall(controller.name)
    }

    @Test
    fun `with nothing ever set up, the next launch starts at setup again`() {
        launch()
        openPracticeWall()

        relaunch()

        waitForText(setupTitle)
    }

    @Test
    fun `its routes stay with it and do not appear on the real wall`() {
        launch()
        openPracticeWall()
        tapHold(1, 1)
        tapSave()
        nameAndSave("Practice route")

        changeController()
        typeAddress(controller.address)
        tapTestAndSave()
        waitForWall(controller.name)

        openRoutes()
        waitForText("No saved routes yet. Light some holds, then tap Save.")
    }
}
