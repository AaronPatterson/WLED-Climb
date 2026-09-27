package com.wledclimb.app.storage

import com.wledclimb.app.grid.Wall
import com.wledclimb.app.grid.fingerprint
import com.wledclimb.app.palette.HoldColor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [RouteRepository.importInto]: what a restore does to routes already here. */
class RouteImportTest {

    private val wall = StoredWall(
        id = 1,
        name = "Climbing Wall",
        controllerMac = "b0cbd8e23458",
        controllerAddress = "http://192.168.30.49",
        width = 3,
        height = 2,
        holdGrid = "111111"
    )

    private val grid = Wall(width = 3, height = 2, cells = List(2) { List(3) { true } })

    private fun file(vararg routes: Pair<String, String>, mac: String = wall.controllerMac) =
        RouteBackupFile(
            wallName = "Climbing Wall",
            controllerMac = mac,
            width = 3,
            height = 2,
            routes = routes.map { (name, holds) ->
                BackupRoute(
                    name = name,
                    holds = holds,
                    wallFingerprint = grid.fingerprint,
                    readOnly = false,
                    createdAt = 10L,
                    updatedAt = 20L
                )
            }
        )

    private fun repository(dao: RouteDao) = RouteRepository(dao) { 5000L }

    @Test
    fun `routes from a file arrive on the wall`() = runTest {
        val dao = InMemoryRouteDao()

        val result = repository(dao).importInto(
            wall = wall,
            file = file("Warmup" to "0,0:1", "Traverse" to "1,1:2"),
            currentFingerprint = grid.fingerprint
        )

        assertEquals(RouteImport(added = 2, skipped = 0, renamed = 0), result)
        assertEquals(listOf("Traverse", "Warmup"), dao.listFor(wall.id).map { it.name })
    }

    @Test
    fun `importing the same file twice changes nothing the second time`() = runTest {
        val dao = InMemoryRouteDao()
        val repository = repository(dao)
        val backup = file("Warmup" to "0,0:1", "Traverse" to "1,1:2")

        repository.importInto(wall, backup, grid.fingerprint)
        val again = repository.importInto(wall, backup, grid.fingerprint)

        assertEquals(RouteImport(added = 0, skipped = 2, renamed = 0), again)
        assertEquals(2, dao.listFor(wall.id).size)
    }

    @Test
    fun `a different route sharing a name is numbered rather than overwriting`() = runTest {
        val dao = InMemoryRouteDao()
        val repository = repository(dao)
        repository.save(wall.id, "Warmup", mapOf(0 to HoldColor.Red), grid)

        val result = repository.importInto(
            wall = wall,
            file = file("Warmup" to "2,1:4"),
            currentFingerprint = grid.fingerprint
        )

        assertEquals(RouteImport(added = 1, skipped = 0, renamed = 1), result)
        val names = dao.listFor(wall.id).map { it.name }
        assertEquals(listOf("Warmup", "Warmup (2)"), names)
        // The route that was already here is untouched - that is the point.
        // Compared against what save() writes rather than a literal, so this
        // says "unchanged" instead of restating which slot Red happens to be.
        val original = dao.listFor(wall.id).first { it.name == "Warmup" }
        assertEquals(
            RouteHolds.serializeSegments(mapOf(0 to HoldColor.Red), grid),
            original.holds
        )
    }

    @Test
    fun `numbering keeps counting rather than colliding with an earlier number`() = runTest {
        val dao = InMemoryRouteDao()
        val repository = repository(dao)
        repository.save(wall.id, "Warmup", mapOf(0 to HoldColor.Red), grid)
        repository.importInto(wall, file("Warmup" to "2,1:4"), grid.fingerprint)

        repository.importInto(wall, file("Warmup" to "1,0:3"), grid.fingerprint)

        assertEquals(
            listOf("Warmup", "Warmup (2)", "Warmup (3)"),
            dao.listFor(wall.id).map { it.name }
        )
    }

    @Test
    fun `nothing is ever removed by an import`() = runTest {
        val dao = InMemoryRouteDao()
        val repository = repository(dao)
        repository.save(wall.id, "Mine", mapOf(0 to HoldColor.Red), grid)

        repository.importInto(wall, file("Theirs" to "1,1:2"), grid.fingerprint)

        assertEquals(listOf("Mine", "Theirs"), dao.listFor(wall.id).map { it.name })
    }

    @Test
    fun `a backup of another wall is refused`() = runTest {
        val dao = InMemoryRouteDao()

        try {
            repository(dao).importInto(
                wall = wall,
                file = file("Warmup" to "0,0:1", mac = "aabbccddeeff"),
                currentFingerprint = grid.fingerprint
            )
            fail("expected another wall's backup to be refused")
        } catch (e: RouteBackupException) {
            assertEquals(RouteBackupProblem.WrongWall, e.problem)
        }

        assertTrue("nothing should have been written", dao.listFor(wall.id).isEmpty())
    }

    @Test
    fun `the same wall in different case is still the same wall`() = runTest {
        val dao = InMemoryRouteDao()

        val result = repository(dao).importInto(
            wall = wall,
            file = file("Warmup" to "0,0:1", mac = "B0CBD8E23458"),
            currentFingerprint = grid.fingerprint
        )

        assertEquals(1, result.added)
    }

    @Test
    fun `an imported route keeps the wall shape it was built against`() = runTest {
        // So it shows as stale here if this wall has changed since - using the
        // current fingerprint instead would quietly claim the route still fits.
        val dao = InMemoryRouteDao()
        val built = RouteBackupFile(
            wallName = "Climbing Wall",
            controllerMac = wall.controllerMac,
            width = 3,
            height = 2,
            routes = listOf(
                BackupRoute("Old", "0,0:1", "0000000000000000", false, 10L, 20L)
            )
        )

        repository(dao).importInto(wall, built, currentFingerprint = grid.fingerprint)

        assertEquals("0000000000000000", dao.listFor(wall.id).single().wallFingerprint)
    }

    @Test
    fun `a route with no recorded time dates from the import`() = runTest {
        val dao = InMemoryRouteDao()
        val handEdited = RouteBackupFile(
            wallName = "Climbing Wall",
            controllerMac = wall.controllerMac,
            width = 3,
            height = 2,
            routes = listOf(BackupRoute("Undated", "0,0:1", grid.fingerprint, false, 0L, 0L))
        )

        repository(dao).importInto(wall, handEdited, grid.fingerprint)

        val stored = dao.listFor(wall.id).single()
        assertEquals(5000L, stored.createdAt)
        assertEquals(5000L, stored.updatedAt)
    }

    @Test
    fun `an exported route can be opened again after being imported`() = runTest {
        // The round trip that matters: holds go out as text and come back as
        // the same lit positions.
        val source = InMemoryRouteDao()
        val holds = mapOf(0 to HoldColor.Red, 4 to HoldColor.Blue)
        repository(source).save(wall.id, "Traverse", holds, grid)
        val document = repository(source).backup(wall, exportedAt = 1L)

        val destination = InMemoryRouteDao()
        repository(destination).importInto(
            wall = wall,
            file = RouteBackup.decode(document),
            currentFingerprint = grid.fingerprint
        )

        val imported = destination.listFor(wall.id).single()
        assertEquals(holds, repository(destination).load(imported.id, grid))
    }
}
