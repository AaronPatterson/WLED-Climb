// Same reason as WallRoutesTest: driving coroutines through a test dispatcher
// means the whole of kotlinx-coroutines-test, all of which is experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteBackup
import com.wledclimb.app.storage.RouteBackupProblem
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Exporting and importing routes through the ViewModel.
 *
 * Streams stand in for the file the person picks, which is the reason the view
 * model takes streams rather than a Uri - nothing here needs a ContentResolver.
 *
 * The fake controller serves a 2x2 wall, so segment indices run 0..3.
 */
class WallBackupTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Fixture {
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()
        val settings = FakeWledSettings()
        val viewModel = WallViewModel(
            client = FakeWledClient(on = true),
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = settings,
            controllerAddress = "http://wall.test",
            io = UnconfinedTestDispatcher()
        )
    }

    private fun Fixture.withRoute(name: String): Fixture {
        viewModel.selectColor(HoldColor.Blue)
        viewModel.toggleHold(segmentIndex = 3)
        viewModel.saveRoute(name)
        return this
    }

    @Test
    fun `exporting writes a backup of the wall's routes`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.withRoute("Traverse")
        runCurrent()
        val sink = ByteArrayOutputStream()

        fixture.viewModel.exportRoutes { sink }
        runCurrent()

        val decoded = RouteBackup.decode(sink.toString("UTF-8"))
        assertEquals(listOf("Traverse"), decoded.routes.map { it.name })
        assertEquals(
            RouteBackupOutcome.Exported(routeCount = 1),
            fixture.viewModel.backupResult.value
        )
    }

    @Test
    fun `a route survives being exported and imported into another install`() = runTest {
        val source = Fixture()
        runCurrent()
        source.withRoute("Traverse")
        runCurrent()
        val file = ByteArrayOutputStream()
        source.viewModel.exportRoutes { file }
        runCurrent()

        // A second install of the app, talking to the same controller, so the
        // stored wall carries the same MAC and the backup is accepted.
        val destination = Fixture()
        runCurrent()
        destination.viewModel.importRoutes { ByteArrayInputStream(file.toByteArray()) }
        runCurrent()

        assertEquals(listOf("Traverse"), destination.viewModel.savedRoutes.value.map { it.name })
        val outcome = destination.viewModel.backupResult.value
        assertTrue("was $outcome", outcome is RouteBackupOutcome.Imported)
        assertEquals(1, (outcome as RouteBackupOutcome.Imported).result.added)
    }

    @Test
    fun `an imported route can be put on the wall`() = runTest {
        val source = Fixture()
        runCurrent()
        source.withRoute("Traverse")
        runCurrent()
        val file = ByteArrayOutputStream()
        source.viewModel.exportRoutes { file }
        runCurrent()

        val destination = Fixture()
        runCurrent()
        destination.viewModel.importRoutes { ByteArrayInputStream(file.toByteArray()) }
        runCurrent()
        val imported = destination.viewModel.savedRoutes.value.single()
        destination.viewModel.loadRoute(imported.id)
        runCurrent()

        val state = destination.viewModel.uiState.value as WallUiState.Ready
        assertEquals(mapOf(3 to HoldColor.Blue), state.litHolds)
    }

    @Test
    fun `picking a file that is not a backup is reported, not crashed on`() = runTest {
        val fixture = Fixture()
        runCurrent()

        fixture.viewModel.importRoutes { ByteArrayInputStream("hello".toByteArray()) }
        runCurrent()

        assertEquals(
            RouteBackupOutcome.Failed(RouteBackupProblem.Unreadable),
            fixture.viewModel.backupResult.value
        )
        assertTrue(fixture.viewModel.savedRoutes.value.isEmpty())
    }

    @Test
    fun `a file that cannot be opened is reported as a plain failure`() = runTest {
        val fixture = Fixture()
        runCurrent()

        fixture.viewModel.importRoutes { throw IOException("permission denied") }
        runCurrent()

        assertEquals(
            RouteBackupOutcome.Failed(problem = null),
            fixture.viewModel.backupResult.value
        )
    }

    @Test
    fun `a stream the picker never provides is reported rather than assumed`() = runTest {
        val fixture = Fixture()
        runCurrent()

        fixture.viewModel.importRoutes { null as InputStream? }
        runCurrent()

        assertEquals(
            RouteBackupOutcome.Failed(problem = null),
            fixture.viewModel.backupResult.value
        )
    }

    @Test
    fun `an export that cannot be written is reported`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.withRoute("Traverse")
        runCurrent()

        fixture.viewModel.exportRoutes { null as OutputStream? }
        runCurrent()

        assertEquals(
            RouteBackupOutcome.Failed(problem = null),
            fixture.viewModel.backupResult.value
        )
    }

    @Test
    fun `the outcome is cleared once it has been shown`() = runTest {
        val fixture = Fixture()
        runCurrent()
        fixture.withRoute("Traverse")
        runCurrent()
        fixture.viewModel.exportRoutes { ByteArrayOutputStream() }
        runCurrent()

        fixture.viewModel.clearBackupResult()

        assertNull(fixture.viewModel.backupResult.value)
    }
}
