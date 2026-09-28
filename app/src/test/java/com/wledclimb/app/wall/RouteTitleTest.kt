package com.wledclimb.app.wall

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Renaming a route in place, on the wall screen.
 *
 * The first composable here with tests, because it is where four defects have
 * been found by using the app rather than by the suite: the field closing the
 * instant it opened, another route's name appearing in it, the name from
 * before the last rename appearing in it, and the field refusing to open a
 * second time. All four were Compose state-lifecycle bugs - state keyed on the
 * wrong thing, or surviving when it should have reset - and none were reachable
 * by a test that only calls functions.
 */
@RunWith(RobolectricTestRunner::class)
class RouteTitleTest {

    @get:Rule
    val compose = createComposeRule()

    /** The name as the screen holds it: Compose state, so a rename redraws. */
    private val name = mutableStateOf<String?>("Warmup")

    private fun showRoute(routeId: Long? = 1L) {
        compose.setContent {
            RouteTitle(
                routeId = routeId,
                routeName = name.value,
                enabled = true,
                onRename = { name.value = it }
            )
        }
    }

    private fun renameTo(from: String, to: String) {
        compose.onNodeWithText(from).performClick()
        compose.onNodeWithText(from).performTextReplacement(to)
        compose.onNodeWithContentDescription("Done renaming").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `tapping the name opens a field still holding it`() {
        showRoute()

        compose.onNodeWithText("Warmup").performClick()

        // Open, and still showing the name - the focus guard exists so that
        // the field's first unfocused report does not close it on the spot.
        compose.onNodeWithContentDescription("Done renaming").assertIsDisplayed()
        compose.onNodeWithText("Warmup").assertIsDisplayed()
    }

    @Test
    fun `renaming takes effect`() {
        showRoute()

        renameTo(from = "Warmup", to = "Traverse")

        assertEquals("Traverse", name.value)
        compose.onNodeWithText("Traverse").assertIsDisplayed()
    }

    @Test
    fun `the field opens again after a rename`() {
        // hasFocused was keyed on the route, so it stayed true after the first
        // rename. The second opening read the field's initial unfocused report
        // as a dismissal and closed instantly - no second rename without
        // switching routes and back.
        showRoute()
        renameTo(from = "Warmup", to = "Traverse")

        compose.onNodeWithText("Traverse").performClick()

        compose.onNodeWithContentDescription("Done renaming").assertIsDisplayed()
    }

    @Test
    fun `reopening shows the current name, not the one before the rename`() {
        // draft was seeded in a remember keyed on the id, which does not re-run
        // when only the name changes - so the field reopened showing the name
        // the route had before it was renamed.
        showRoute()
        renameTo(from = "Warmup", to = "Traverse")

        compose.onNodeWithText("Traverse").performClick()

        compose.onNodeWithText("Traverse").assertIsDisplayed()
        compose.onNodeWithText("Warmup").assertDoesNotExist()
    }

    @Test
    fun `work with no name yet cannot be renamed`() {
        name.value = null
        showRoute(routeId = null)

        // A route gets its name by being saved, so there is nothing to rename.
        compose.onNodeWithText("Unsaved route").performClick()

        compose.onNodeWithContentDescription("Done renaming").assertDoesNotExist()
    }
}
