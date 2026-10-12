package com.wledclimb.app.e2e

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Exporting routes to a file and importing them back.
 *
 * The system file picker is the one thing here that is not this app, and a JVM
 * test cannot tap through it, so Robolectric answers it: the request the app
 * makes is taken off the activity, and a file - an in-memory stream registered
 * against a content URI - is handed back as though someone had chosen it.
 * Everything either side of that is the app as shipped.
 */
class BackupTest : EndToEndTest() {

    private val file: Uri = Uri.parse("content://com.wledclimb.test/routes.json")

    private fun saveNew(name: String) {
        tapSave()
        nameAndSave(name)
        assertTitle(name)
    }

    /** Warmup (1,1) and Traverse (3,3) saved on the connected wall. */
    private fun twoSavedRoutes() {
        launchConnected()
        tapHold(1, 1)
        saveNew("Warmup")
        openRoutes()
        tapNew()
        tapHold(3, 3)
        saveNew("Traverse")
    }

    /** Answers the picker the app just opened, as though [uri] had been chosen. */
    private fun choose(uri: Uri) {
        onActivity { activity ->
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult
            assertNotNull("the app should have opened a file picker", request)
            shadow.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        }
        compose.waitForIdle()
    }

    private fun cancelPicker() {
        onActivity { activity ->
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult
            assertNotNull("the app should have opened a file picker", request)
            shadow.receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        }
        compose.waitForIdle()
    }

    /** Exports this wall's routes and returns the file's contents. */
    private fun export(): ByteArray {
        val sink = ByteArrayOutputStream()
        openMenu()
        menuItem("Export routes").performClick()
        onActivity { shadowOf(it.contentResolver).registerOutputStream(file, sink) }
        choose(file)
        waitForText("Routes exported")
        dialogButton("OK").performClick()
        waitForGone(isDialog())
        return sink.toByteArray()
    }

    private fun startImport(contents: ByteArray) {
        openMenu()
        menuItem("Import routes").performClick()
        onActivity {
            shadowOf(it.contentResolver).registerInputStream(file, ByteArrayInputStream(contents))
        }
        choose(file)
    }

    @Test
    fun `export is not offered until there is a route to export`() {
        launchConnected()

        openMenu()

        menuItem("Export routes").assertIsNotEnabled()
        menuItem("Import routes").assertIsEnabled()
    }

    @Test
    fun `exporting writes every route to the chosen file and says so`() {
        twoSavedRoutes()

        val sink = ByteArrayOutputStream()
        openMenu()
        menuItem("Export routes").performClick()
        onActivity { shadowOf(it.contentResolver).registerOutputStream(file, sink) }
        choose(file)

        waitForText("Routes exported")
        assertTrue(exists(inDialog(hasText("2 routes were written to the file you chose."))))
        val written = sink.toString(Charsets.UTF_8)
        assertTrue(written.contains("\"Warmup\""))
        assertTrue(written.contains("\"Traverse\""))
    }

    @Test
    fun `routes exported from one install come back in a fresh one`() {
        // The whole point: a reinstall, or a new phone, or the debug and
        // release builds side by side, no longer costs the routes.
        twoSavedRoutes()
        val backup = export()

        reinstall()
        connect()
        startImport(backup)

        waitForText("Routes imported")
        assertTrue(exists(inDialog(hasText("2 routes added."))))
        dialogButton("OK").performClick()
        openRoutes()
        assertEquals(setOf("Warmup", "Traverse"), savedRouteNames().toSet())
        openRoute("Warmup")
        assertLit(1, 1, "Red")
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() {
        twoSavedRoutes()
        val backup = export()

        startImport(backup)

        waitForText("Nothing new - every route in that file is already here.")
        dialogButton("OK").performClick()
        openRoutes()
        assertEquals(2, savedRouteNames().size)
    }

    @Test
    fun `a backup of a different wall is refused and explained`() {
        twoSavedRoutes()
        val backup = export()
        val otherWall = FakeWledController().apply {
            name = "Garage Wall"
            mac = "aabbccddeeff"
        }
        try {
            reinstall()
            connect(otherWall)

            startImport(backup)

            waitForText("That did not work")
            assertTrue(exists(inDialog(hasText("holds routes for a different climbing wall", substring = true))))
            dialogButton("OK").performClick()
            openRoutes()
            waitForText("No saved routes yet. Light some holds, then tap Save.")
        } finally {
            otherWall.close()
        }
    }

    @Test
    fun `a file that is not a backup is refused and explained`() {
        launchConnected()

        startImport("these are not routes".toByteArray())

        waitForText("That did not work")
        assertTrue(exists(inDialog(hasText("That file could not be read", substring = true))))
    }

    @Test
    fun `closing the picker without choosing does nothing`() {
        // Someone changed their mind. That is not a failure and deserves no
        // message.
        twoSavedRoutes()

        openMenu()
        menuItem("Import routes").performClick()
        cancelPicker()

        assertFalse(isDialogShowing())
        openRoutes()
        assertEquals(2, savedRouteNames().size)
    }
}
