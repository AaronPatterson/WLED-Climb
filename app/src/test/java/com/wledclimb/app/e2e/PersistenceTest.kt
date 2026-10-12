package com.wledclimb.app.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What survives the app being closed, and what survives a rotation.
 *
 * Closing is [relaunch]: the activity and its view models are gone and the
 * database is reopened from disk, as after being killed in the background.
 * A rotation is [recreate], where state that is meant to survive, survives.
 */
class PersistenceTest : EndToEndTest() {

    private fun saveNew(name: String) {
        tapSave()
        nameAndSave(name)
        assertTitle(name)
    }

    @Test
    fun `unsaved work survives the app being closed`() {
        launchConnected()
        tapHold(1, 1)
        tapHold(2, 2)

        relaunch()

        waitForWall(controller.name)
        assertTitle("Unsaved route")
        assertLit(1, 1, "Red")
        assertLit(2, 2, "Red")
        // Still unsaved: nothing was saved on the way out.
        compose.onNode(hasContentDescription("Save this route")).assertIsEnabled()
    }

    @Test
    fun `edits to a saved route come back as edits, and are not saved`() {
        // Closing the app must not save on anyone's behalf: the edit returns
        // as though it had just been made, and Reset still undoes it.
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        tapHold(2, 2)

        relaunch()

        waitForWall(controller.name)
        assertTitle("Warmup")
        assertLit(2, 2, "Red")
        tapReset()
        waitForText("Reset Warmup?")
        dialogButton("Reset").performClick()
        assertUnlit(2, 2, "the saved route never had this hold")
        assertLit(1, 1, "Red")
    }

    @Test
    fun `the route that was open is open again after the app is closed`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        openRoutes()
        tapNew()
        tapHold(3, 3)
        saveNew("Traverse")
        openRoutes()
        openRoute("Warmup")
        assertTitle("Warmup")

        relaunch()

        waitForWall(controller.name)
        assertTitle("Warmup")
        assertLit(1, 1, "Red")
    }

    @Test
    fun `opening the app does not take over the wall`() {
        // Several devices share one wall. Opening the app shows what it was
        // last showing, but does not send it - someone else may be climbing
        // what is up there now.
        //
        // Checked by order rather than by waiting for nothing: a push on
        // opening goes out just after the controller answers, so asserting
        // "nothing was sent" the moment it is connected can finish before
        // that push lands. Here the first thing sent after reopening has to
        // be the hold tapped afterwards - a push on opening would arrive
        // ahead of it, carrying only what was restored.
        launchConnected()
        tapHold(1, 1)
        eventually { controller.showing.isNotEmpty() }
        val sentBefore = controller.pushes.size

        relaunch()
        waitForWall(controller.name)
        assertLit(1, 1, "Red")
        waitForPower("Turn wall off")
        tapHold(2, 2)

        eventually { controller.showing.keys == setOf(segment(1, 1), segment(2, 2)) }
        assertEquals(
            "only the tap should have been sent since reopening",
            1,
            controller.pushes.size - sentBefore
        )
    }

    @Test
    fun `a name being typed survives a rotation`() {
        launchConnected()
        tapHold(1, 1)
        tapSave()
        waitFor(inDialog(hasSetTextAction()))
        typeInDialog("Warm")

        recreate()

        waitFor(inDialog(hasSetTextAction()))
        assertEquals("Warm", dialogFieldText())
    }

    @Test
    fun `an open confirmation survives a rotation and still names its route`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        openRoutes()
        routeMenu("Warmup", "Delete")
        waitForText("Delete Warmup?")

        recreate()

        waitForText("Delete Warmup?")
    }

    @Test
    fun `the wall survives a rotation unchanged`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        tapHold(2, 2)

        recreate()

        waitForWall(controller.name)
        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        assertLit(2, 2, "Red")
        compose.onNode(hasContentDescription("Save this route")).assertIsEnabled()
    }
}
