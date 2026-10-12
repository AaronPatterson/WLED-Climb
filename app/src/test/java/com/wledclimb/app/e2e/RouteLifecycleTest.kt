package com.wledclimb.app.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building, saving, opening, renaming, resetting and deleting routes.
 *
 * The 3x3 wall from [FakeWledController]: holds are named by 1-based column
 * and row, as a screen reader names them.
 */
class RouteLifecycleTest : EndToEndTest() {

    private fun saveNew(name: String) {
        tapSave()
        nameAndSave(name)
        assertTitle(name)
    }

    /** From an open route with nothing unsaved, to a blank wall. */
    private fun startNewRoute() {
        openRoutes()
        tapNew()
        waitFor(hold(1, 1))
    }

    // --- the wall ---------------------------------------------------------

    @Test
    fun `a wall starts as an unsaved route with nothing lit and nothing to save`() {
        launchConnected()

        assertTitle("Unsaved route")
        assertEquals(0, litCount())
        compose.onNode(hasContentDescription("Save this route")).assertIsNotEnabled()
        compose.onNode(hasContentDescription("Reset")).assertIsNotEnabled()
        // Nothing open, so nothing to copy.
        compose.onNode(hasContentDescription("Save as new")).assertIsNotEnabled()
    }

    @Test
    fun `a tapped hold lights in the chosen colour and is sent to the wall`() {
        launchConnected()

        pickColour("Blue")
        tapHold(1, 1)

        assertLit(1, 1, "Blue")
        eventually { controller.showing == mapOf(segment(1, 1) to controller.showing[segment(1, 1)]) }
    }

    @Test
    fun `tapping a lit hold in the same colour turns it off`() {
        launchConnected()
        tapHold(1, 1)

        tapHold(1, 1)

        assertUnlit(1, 1)
        eventually { controller.showing.isEmpty() }
    }

    @Test
    fun `tapping a lit hold in another colour changes its colour`() {
        launchConnected()
        tapHold(1, 1)

        pickColour("Green")
        tapHold(1, 1)

        assertLit(1, 1, "Green")
        assertEquals(1, litCount())
    }

    @Test
    fun `clearing the wall turns every hold off`() {
        launchConnected()
        tapHold(1, 1)
        tapHold(3, 3)

        compose.onNode(hasContentDescription("Clear wall")).performClick()

        eventually { litCount() == 0 }
        eventually { controller.showing.isEmpty() }
    }

    // --- saving -----------------------------------------------------------

    @Test
    fun `saving a new route asks for a name, then opens it`() {
        launchConnected()
        tapHold(1, 1)

        tapSave()

        waitForText("Save route")
        nameAndSave("Warmup")
        assertTitle("Warmup")
        // Saved, so nothing left to save.
        compose.onNode(hasContentDescription("Save this route")).assertIsNotEnabled()
        openRoutes()
        assertEquals(listOf("Warmup"), savedRouteNames())
    }

    @Test
    fun `a route cannot be saved with a blank name`() {
        launchConnected()
        tapHold(1, 1)
        tapSave()
        waitFor(inDialog(hasSetTextAction()))

        dialogButton("Save").assertIsNotEnabled()
        typeInDialog("   ")
        dialogButton("Save").assertIsNotEnabled()
    }

    @Test
    fun `cancelling the name saves nothing and keeps the holds`() {
        launchConnected()
        tapHold(1, 1)
        tapSave()
        waitFor(inDialog(hasSetTextAction()))
        typeInDialog("Warmup")

        dialogButton("Cancel").performClick()

        waitForGone(inDialog(hasSetTextAction()))
        assertTitle("Unsaved route")
        assertLit(1, 1, "Red")
        openRoutes()
        waitForText("No saved routes yet. Light some holds, then tap Save.")
    }

    @Test
    fun `saving an open route that has changed saves it in place, without asking`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        tapHold(2, 2)
        compose.onNode(hasContentDescription("Save this route")).assertIsEnabled()
        tapSave()

        assertFalse("no name should be asked for", isDialogShowing())
        waitForDisabled("Save this route")
        assertTitle("Warmup")
        openRoutes()
        assertEquals(listOf("Warmup"), savedRouteNames())
    }

    @Test
    fun `save as new keeps the original and opens the copy`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        tapHold(2, 2)

        tapSaveAsNew()
        waitForText("Save as a new route")
        // Offered the current name to edit, rather than an empty box.
        assertEquals("Warmup", dialogFieldText())
        typeInDialog("Warmup 2")
        dialogButton("Save").performClick()

        assertTitle("Warmup 2")
        openRoutes()
        assertEquals(setOf("Warmup", "Warmup 2"), savedRouteNames().toSet())
        openRoute("Warmup")
        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        assertUnlit(2, 2, "the original keeps only what it had")
    }

    // --- opening and starting over -----------------------------------------

    @Test
    fun `opening a route puts it on the wall and in the title`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        startNewRoute()
        tapHold(3, 3)
        saveNew("Traverse")

        openRoutes()
        openRoute("Warmup")

        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        assertUnlit(3, 3)
        eventually { controller.showing.keys == setOf(segment(1, 1)) }
    }

    @Test
    fun `a new route with nothing unsaved starts blank straight away`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        tapNew()

        assertFalse("nothing to lose, so nothing to ask", isDialogShowing())
        assertTitle("Unsaved route")
        assertEquals(0, litCount())
        eventually { controller.showing.isEmpty() }
    }

    // --- resetting --------------------------------------------------------

    @Test
    fun `reset brings back the saved route once confirmed`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        tapHold(2, 2)

        tapReset()
        waitForText("Reset Warmup?")
        dialogButton("Reset").performClick()

        eventually { isUnlit(2, 2) }
        assertLit(1, 1, "Red")
        compose.onNode(hasContentDescription("Reset")).assertIsNotEnabled()
    }

    @Test
    fun `cancelling a reset keeps the edits`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        tapHold(2, 2)

        tapReset()
        waitForText("Reset Warmup?")
        dialogButton("Cancel").performClick()

        assertLit(2, 2, "Red")
        compose.onNode(hasContentDescription("Save this route")).assertIsEnabled()
    }

    @Test
    fun `resetting a route that was never saved clears the wall`() {
        launchConnected()
        tapHold(1, 1)
        tapHold(2, 2)

        tapReset()
        waitForText("Clear this route?")
        dialogButton("Reset").performClick()

        eventually { litCount() == 0 }
        assertTitle("Unsaved route")
    }

    // --- deleting ---------------------------------------------------------

    @Test
    fun `deleting the open route clears the wall and leaves an unsaved route`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        routeMenu("Warmup", "Delete")
        waitForText("Delete Warmup?")
        dialogButton("Delete").performClick()

        waitForText("No saved routes yet. Light some holds, then tap Save.")
        backToWall()
        assertTitle("Unsaved route")
        assertEquals(0, litCount())
        eventually { controller.showing.isEmpty() }
        // A blank wall where a deleted route was is not unsaved work.
        compose.onNode(hasContentDescription("Save this route")).assertIsNotEnabled()
    }

    @Test
    fun `deleting the open route does not promise to leave the wall lit`() {
        // The confirmation said "The wall stays lit as it is." for every
        // route, while deleting the one on the wall clears it.
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        routeMenu("Warmup", "Delete")
        waitForText("Delete Warmup?")

        assertFalse(exists(inDialog(hasText("The wall stays lit as it is.", substring = true))))
    }

    @Test
    fun `deleting a route that is not open says the wall is left alone`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        startNewRoute()
        tapHold(3, 3)
        saveNew("Traverse")

        openRoutes()
        routeMenu("Warmup", "Delete")
        waitForText("Delete Warmup?")

        assertTrue(exists(inDialog(hasText("The wall is not changed.", substring = true))))
    }

    @Test
    fun `deleting the open route says the wall will be cleared`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        routeMenu("Warmup", "Delete")
        waitForText("Delete Warmup?")

        assertTrue(exists(inDialog(hasText("the wall is cleared", substring = true))))
    }

    @Test
    fun `deleting another route leaves the wall alone`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        startNewRoute()
        tapHold(3, 3)
        saveNew("Traverse")

        openRoutes()
        routeMenu("Warmup", "Delete")
        dialogButton("Delete").performClick()

        eventually { savedRouteNames() == listOf("Traverse") }
        backToWall()
        assertTitle("Traverse")
        assertLit(3, 3, "Red")
    }

    @Test
    fun `cancelling a delete keeps the route`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        routeMenu("Warmup", "Delete")
        dialogButton("Cancel").performClick()

        waitForGone(inDialog(hasText("Delete Warmup?")))
        assertEquals(listOf("Warmup"), savedRouteNames())
    }

    // --- renaming ---------------------------------------------------------

    @Test
    fun `renaming from the list offers that route's own name`() {
        // The rename dialog once remembered its text by position on screen,
        // so renaming a second route opened with the first one's name.
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        startNewRoute()
        tapHold(3, 3)
        saveNew("Traverse")

        openRoutes()
        routeMenu("Warmup", "Rename")
        assertEquals("Warmup", dialogFieldText())
        dialogButton("Cancel").performClick()
        waitForGone(inDialog(hasSetTextAction()))

        routeMenu("Traverse", "Rename")
        assertEquals("Traverse", dialogFieldText())
    }

    @Test
    fun `renaming from the list renames the route, and the title follows`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        routeMenu("Warmup", "Rename")
        typeInDialog("Crimp Line")
        dialogButton("Rename").performClick()

        eventually { savedRouteNames() == listOf("Crimp Line") }
        backToWall()
        assertTitle("Crimp Line")
    }

    @Test
    fun `cancelling a rename keeps the old name`() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        openRoutes()
        routeMenu("Warmup", "Rename")
        typeInDialog("Something Else")
        dialogButton("Cancel").performClick()

        waitForGone(inDialog(hasSetTextAction()))
        assertEquals(listOf("Warmup"), savedRouteNames())
    }

    private fun renameInPlace(from: String, to: String) {
        compose.onNode(hasText(from)).performClick()
        waitFor(hasContentDescription("Done renaming"))
        compose.onNode(hasSetTextAction()).performTextReplacement(to)
        compose.onNode(hasContentDescription("Done renaming")).performClick()
        assertTitle(to)
    }

    @Test
    fun `a route can be renamed in place more than once`() {
        // The field used to close the instant it opened on the second rename
        // of the same route, so a rename needed a switch away and back.
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")

        renameInPlace("Warmup", "Traverse")
        renameInPlace("Traverse", "Crux")

        openRoutes()
        assertEquals(listOf("Crux"), savedRouteNames())
    }

    @Test
    fun `renaming in place offers the open route's current name`() {
        // Two defects: the field once showed the name from before the last
        // rename, and before that, another route's name after switching.
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        startNewRoute()
        tapHold(3, 3)
        saveNew("Traverse")
        renameInPlace("Traverse", "Traverse 2")

        openRoutes()
        openRoute("Warmup")
        compose.onNode(hasText("Warmup")).performClick()
        waitFor(hasContentDescription("Done renaming"))

        assertEquals(
            "Warmup",
            compose.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        )
    }

    @Test
    fun `work with no name yet cannot be renamed in place`() {
        launchConnected()
        tapHold(1, 1)

        compose.onNode(hasText("Unsaved route")).performClick()

        assertFalse(exists(hasContentDescription("Done renaming")))
    }
}
