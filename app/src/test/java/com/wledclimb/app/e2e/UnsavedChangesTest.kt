package com.wledclimb.app.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "save first?" question, asked when switching away from unsaved work -
 * the one place the app can lose something someone made.
 *
 * It has three answers, and the third is not a button: dismissing it keeps the
 * work and does nothing else. Saving and discarding both go on to do what was
 * interrupted.
 */
class UnsavedChangesTest : EndToEndTest() {

    private fun saveNew(name: String) {
        tapSave()
        nameAndSave(name)
        assertTitle(name)
    }

    private fun startNewRoute() {
        openRoutes()
        tapNew()
        waitFor(hold(1, 1))
    }

    /** Warmup, saved with (1,1), now showing (2,2) as well and not saved. */
    private fun editedWarmup() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        tapHold(2, 2)
    }

    /** Warmup (1,1) and Traverse (3,3) saved, Traverse open with (2,2) added unsaved. */
    private fun twoRoutesWithTraverseEdited() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        startNewRoute()
        tapHold(3, 3)
        saveNew("Traverse")
        tapHold(2, 2)
    }

    @Test
    fun `starting a new route with unsaved edits asks first`() {
        editedWarmup()

        openRoutes()
        tapNew()

        waitForText("Save changes to Warmup?")
    }

    @Test
    fun `dismissing the question keeps the edits and changes nothing`() {
        editedWarmup()
        openRoutes()
        tapNew()
        waitForText("Save changes to Warmup?")

        pressBack()

        waitForGone(isDialog())
        backToWall()
        assertTitle("Warmup")
        assertLit(2, 2, "Red")
        compose.onNode(hasContentDescription("Save this route")).assertIsEnabled()
    }

    @Test
    fun `discarding drops the edits and starts the new route`() {
        editedWarmup()
        openRoutes()
        tapNew()

        dialogButton("Discard").performClick()

        assertTitle("Unsaved route")
        assertEquals(0, litCount())
        openRoutes()
        openRoute("Warmup")
        assertLit(1, 1, "Red", "the saved route is untouched")
        assertUnlit(2, 2, "the discarded edit is gone")
    }

    @Test
    fun `saving an open route saves it in place, then starts the new route`() {
        // Saving from here once always asked for a name - which saved a copy
        // under a new name and left the original without the edits.
        editedWarmup()
        openRoutes()
        tapNew()

        dialogButton("Save").performClick()

        assertTitle("Unsaved route")
        assertFalse("no name should be asked for", exists(inDialog(hasSetTextAction())))
        openRoutes()
        assertEquals(listOf("Warmup"), savedRouteNames())
        openRoute("Warmup")
        assertLit(1, 1, "Red")
        assertLit(2, 2, "Red", "the edit was saved into it")
    }

    @Test
    fun `saving unnamed work asks for a name, then starts the new route`() {
        // The save and the new route are two operations from one tap, and
        // they once finished in the wrong order: the blank wall that followed
        // was titled with the name just given.
        launchConnected()
        tapHold(1, 1)
        openRoutes()
        tapNew()
        waitForText("Save this route?")

        dialogButton("Save").performClick()
        nameAndSave("Warmup")

        assertTitle("Unsaved route")
        assertEquals(0, litCount())
        openRoutes()
        assertEquals(listOf("Warmup"), savedRouteNames())
    }

    @Test
    fun `cancelling the name saves nothing and keeps the work`() {
        launchConnected()
        tapHold(1, 1)
        openRoutes()
        tapNew()
        dialogButton("Save").performClick()
        waitFor(inDialog(hasSetTextAction()))

        dialogButton("Cancel").performClick()

        waitForGone(isDialog())
        waitForText("No saved routes yet. Light some holds, then tap Save.")
        backToWall()
        assertTitle("Unsaved route")
        assertLit(1, 1, "Red")
    }

    @Test
    fun `opening another route with unsaved edits asks, and discard opens it`() {
        twoRoutesWithTraverseEdited()

        openRoutes()
        openRoute("Warmup")
        waitForText("Save changes to Traverse?")
        dialogButton("Discard").performClick()

        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        assertUnlit(3, 3)
        openRoutes()
        openRoute("Traverse")
        assertLit(3, 3, "Red")
        assertUnlit(2, 2, "discarded")
    }

    @Test
    fun `saving then opening another route opens that one, with the save kept`() {
        // The other half of the same race: save, then open the route being
        // switched to. The save must not finish by reselecting its own route.
        twoRoutesWithTraverseEdited()

        openRoutes()
        openRoute("Warmup")
        waitForText("Save changes to Traverse?")
        dialogButton("Save").performClick()

        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        openRoutes()
        openRoute("Traverse")
        assertLit(3, 3, "Red")
        assertLit(2, 2, "Red", "saved before switching")
    }

    @Test
    fun `the question survives a rotation and still does what it interrupted`() {
        editedWarmup()
        openRoutes()
        tapNew()
        waitForText("Save changes to Warmup?")

        recreate()

        waitForText("Save changes to Warmup?")
        dialogButton("Discard").performClick()
        assertTitle("Unsaved route")
        assertEquals(0, litCount())
    }
}
