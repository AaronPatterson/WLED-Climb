package com.wledclimb.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

class RouteBackupTest {

    private val wall = StoredWall(
        id = 7,
        name = "Climbing Wall",
        controllerMac = "b0cbd8e23458",
        controllerAddress = "http://192.168.30.49",
        width = 12,
        height = 12,
        holdGrid = "1".repeat(144)
    )

    private fun route(name: String, holds: String) = StoredRoute(
        id = 42,
        wallId = 7,
        name = name,
        holds = holds,
        wallFingerprint = "9d01a11c8e5128fa",
        readOnly = false,
        createdAt = 1000L,
        updatedAt = 2000L
    )

    private fun problemOf(text: String): RouteBackupProblem? =
        try {
            RouteBackup.decode(text)
            fail("expected $text to be refused")
            null
        } catch (e: RouteBackupException) {
            e.problem
        }

    @Test
    fun `a backup survives the round trip`() {
        val routes = listOf(route("Warmup", "0,0:1;3,4:2"), route("Traverse", "1,1:3"))

        val decoded = RouteBackup.decode(RouteBackup.encode(wall, routes, exportedAt = 5L))

        assertEquals("Climbing Wall", decoded.wallName)
        assertEquals("b0cbd8e23458", decoded.controllerMac)
        assertEquals(12, decoded.width)
        assertEquals(12, decoded.height)
        assertEquals(listOf("Warmup", "Traverse"), decoded.routes.map { it.name })
        assertEquals(listOf("0,0:1;3,4:2", "1,1:3"), decoded.routes.map { it.holds })
        assertEquals("9d01a11c8e5128fa", decoded.routes.first().wallFingerprint)
        assertEquals(1000L, decoded.routes.first().createdAt)
        assertEquals(2000L, decoded.routes.first().updatedAt)
    }

    @Test
    fun `row ids do not travel - they mean nothing in another database`() {
        val encoded = RouteBackup.encode(wall, listOf(route("Warmup", "0,0:1")), exportedAt = 5L)

        val stored = JSONObject(encoded).getJSONArray("routes").getJSONObject(0)
        assertTrue("id leaked into the file", !stored.has("id"))
        assertTrue("wallId leaked into the file", !stored.has("wallId"))
    }

    @Test
    fun `the holds text is carried verbatim, so a palette change does not rewrite it`() {
        // Slots, not colours - the whole reason a backup stays valid across a
        // palette being retuned.
        val encoded = RouteBackup.encode(wall, listOf(route("Warmup", "2,3:4")), exportedAt = 5L)

        assertEquals("2,3:4", RouteBackup.decode(encoded).routes.single().holds)
    }

    @Test
    fun `something that is not json at all is refused`() {
        assertEquals(RouteBackupProblem.Unreadable, problemOf("not a file we wrote"))
    }

    @Test
    fun `json from somewhere else is refused`() {
        assertEquals(RouteBackupProblem.NotABackup, problemOf("""{"routes":[]}"""))
    }

    @Test
    fun `a file with no wall is refused - there is nothing to check it against`() {
        val text = """{"format":"wled-climb-routes","version":1,"routes":[]}"""
        assertEquals(RouteBackupProblem.NotABackup, problemOf(text))
    }

    @Test
    fun `a newer format version is refused rather than half read`() {
        val encoded = JSONObject(
            RouteBackup.encode(wall, listOf(route("Warmup", "0,0:1")), exportedAt = 5L)
        ).put("version", RouteBackup.VERSION + 1).toString()

        assertEquals(RouteBackupProblem.TooNew, problemOf(encoded))
    }

    @Test
    fun `a backup with no routes in it is refused`() {
        assertEquals(RouteBackupProblem.Empty, problemOf(RouteBackup.encode(wall, emptyList(), 5L)))
    }

    @Test
    fun `one unusable entry does not cost the rest of the file`() {
        val encoded = JSONObject(
            RouteBackup.encode(wall, listOf(route("Warmup", "0,0:1")), exportedAt = 5L)
        )
        // A route with no name cannot be told apart in a list, so it is dropped
        // - but the file still imports.
        encoded.getJSONArray("routes").put(JSONObject().put("holds", "1,1:1"))

        val decoded = RouteBackup.decode(encoded.toString())

        assertEquals(listOf("Warmup"), decoded.routes.map { it.name })
    }
}
