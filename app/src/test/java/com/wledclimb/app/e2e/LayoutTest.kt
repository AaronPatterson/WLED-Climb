package com.wledclimb.app.e2e

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.wledclimb.app.storage.ClimbDatabase
import com.wledclimb.app.storage.StoredRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** One pane at a time on a phone, held upright. */
class PhoneLayoutTest : EndToEndTest() {

    @Test
    fun `the routes list replaces the wall, and the same button brings it back`() {
        launchConnected()

        openRoutes()
        assertFalse("the wall should be covered by the list", exists(hold(1, 1)))

        backToWall()
        assertFalse(exists(hasText("Routes")))
    }

    @Test
    fun `a phone is held in portrait`() {
        // Landscape on a phone left no room for the grid, so it is refused
        // below the width where both fit.
        launchConnected()

        onActivity {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, it.requestedOrientation)
        }
    }
}

/**
 * The routes list beside the wall on a tablet.
 *
 * Kept clear of dialogs with text fields - see [EndToEndTest] on why those
 * cannot be driven on a screen this size under Robolectric. Where a test needs
 * saved routes it writes them straight to the database instead of through the
 * naming dialog: what is under test here is the layout, not saving.
 */
@Config(qualifiers = "w1280dp-h800dp-land")
class TabletLayoutTest : EndToEndTest() {

    private fun seedRoute(name: String, holds: String) {
        runBlocking {
            val database = ClimbDatabase.instance(context)
            val wall = database.walls().byMac(controller.mac) ?: error("wall not stored yet")
            database.routes().insert(
                StoredRoute(
                    wallId = wall.id,
                    name = name,
                    holds = holds,
                    wallFingerprint = "",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        }
    }

    @Test
    fun `the routes list and the wall are both on screen`() {
        launchConnected()

        waitForText("Routes")
        assertTrue(exists(hold(1, 1)))
    }

    @Test
    fun `the routes button puts the list away and brings it back`() {
        // On a tablet this button once did nothing at all: the scaffold reads
        // its layout from a navigator remembered without it, so a new one was
        // never taken up.
        launchConnected()
        waitForText("Routes")

        compose.onNode(androidx.compose.ui.test.hasContentDescription("Saved routes")).performClick()
        waitForGone(hasText("Routes"))
        assertTrue("the wall stays", exists(hold(1, 1)))

        compose.onNode(androidx.compose.ui.test.hasContentDescription("Saved routes")).performClick()
        waitForText("Routes")
        assertTrue(exists(hold(1, 1)))
    }

    @Test
    fun `a put-away list stays away across a rotation`() {
        // Turning a tablet is not a request to bring the list back.
        launchConnected()
        waitForText("Routes")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Saved routes")).performClick()
        waitForGone(hasText("Routes"))

        recreate()

        waitFor(hold(1, 1))
        assertFalse(exists(hasText("Routes")))
    }

    @Test
    fun `opening a route from the list keeps both panes`() {
        launchConnected()
        seedRoute("Warmup", "0,0:0")
        waitForText("Warmup")

        openRoute("Warmup")

        assertTitle("Warmup")
        assertLit(1, 1, "Red")
        assertTrue("the list is still there", exists(hasText("Routes")))
    }

    @Test
    fun `a tablet is not held in portrait`() {
        launchConnected()

        onActivity {
            assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, it.requestedOrientation)
        }
    }
}
