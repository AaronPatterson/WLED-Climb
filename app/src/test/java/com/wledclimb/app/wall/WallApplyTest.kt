// Every test here drives coroutines through a test dispatcher, and the whole
// of kotlinx-coroutines-test is marked experimental - runTest, runCurrent,
// UnconfinedTestDispatcher, setMain. There is no non-experimental way to write
// these tests, so the opt-in is an acknowledgement rather than a choice, and
// it belongs at the top of the file rather than repeated at every call site.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Applying a route to the wall, and the auto-apply setting that skips it.
 *
 * With auto-apply on, every change goes to the wall as it always has. With it
 * off, edits stay on this device until applied, and after that the wall
 * follows edits to the same work until something else is opened.
 *
 * The fake controller serves a 2x2 wall, so segment indices run 0..3.
 */
class WallApplyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Fixture(autoApply: Boolean) {
        val client = FakeWledClient(on = true)
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()
        val settings = FakeWledSettings(initialAutoApply = autoApply)

        fun open() = WallViewModel(
            client = client,
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = settings,
            controllerAddress = "http://wall.test"
        )
    }

    private fun connected(viewModel: WallViewModel): WallUiState.Connected =
        viewModel.uiState.value as? WallUiState.Connected
            ?: error("Expected Connected but was " + viewModel.uiState.value)

    @Test
    fun `with auto-apply off, a tapped hold changes the screen and not the wall`() = runTest {
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()

        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()

        assertEquals(mapOf(2 to HoldColor.Red), connected(viewModel).litHolds)
        assertFalse(connected(viewModel).applied)
        assertTrue(fixture.client.pushedHolds.isEmpty())
    }

    @Test
    fun `with auto-apply off, edits are still kept as a draft`() = runTest {
        // Not reaching the wall is not the same as not counting.
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()

        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()

        assertEquals("0,1:0", fixture.wallDao.byId(connected(viewModel).wallId!!)?.draftHolds)
    }

    @Test
    fun `applying pushes the whole route and marks it applied`() = runTest {
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        viewModel.applyRoute()
        runCurrent()

        assertTrue(connected(viewModel).applied)
        assertEquals(listOf(mapOf(0 to "FF0000", 3 to "FF0000")), fixture.client.pushedHolds)
        assertEquals(4, fixture.client.lastPixelCount)
    }

    @Test
    fun `after applying, the wall follows edits`() = runTest {
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        viewModel.applyRoute()
        runCurrent()

        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        assertTrue(connected(viewModel).applied)
        assertEquals(mapOf(0 to "FF0000", 3 to "FF0000"), fixture.client.pushedHolds.last())
    }

    @Test
    fun `opening another route leaves the applied one on the wall`() = runTest {
        // The case auto-apply off exists for: someone is climbing what was
        // applied, and the next route is being built on this device.
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 1)
        viewModel.saveRoute("Other")
        runCurrent()
        val other = connected(viewModel).selectedRouteId!!
        viewModel.newRoute()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        viewModel.applyRoute()
        runCurrent()
        val pushes = fixture.client.pushedHolds.size

        viewModel.loadRoute(other)
        runCurrent()
        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()

        assertFalse(connected(viewModel).applied)
        assertEquals(pushes, fixture.client.pushedHolds.size)
    }

    @Test
    fun `starting a new route leaves the applied one on the wall`() = runTest {
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        viewModel.applyRoute()
        runCurrent()
        val pushes = fixture.client.pushedHolds.size

        viewModel.newRoute()
        runCurrent()

        assertFalse(connected(viewModel).applied)
        assertEquals(pushes, fixture.client.pushedHolds.size)
    }

    @Test
    fun `resetting an applied route takes the wall back with it`() = runTest {
        // A reset is the same work, not different work.
        val fixture = Fixture(autoApply = false)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        viewModel.saveRoute("Traverse")
        runCurrent()
        viewModel.applyRoute()
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        viewModel.revertRoute()
        runCurrent()

        assertTrue(connected(viewModel).applied)
        assertEquals(mapOf(0 to "FF0000"), fixture.client.pushedHolds.last())
    }

    @Test
    fun `with auto-apply on, opening a route puts it on the wall`() = runTest {
        val fixture = Fixture(autoApply = true)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 1)
        viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = connected(viewModel).selectedRouteId!!
        viewModel.newRoute()
        runCurrent()

        viewModel.loadRoute(routeId)
        runCurrent()

        assertTrue(connected(viewModel).applied)
        assertEquals(mapOf(1 to "FF0000"), fixture.client.pushedHolds.last())
    }

    @Test
    fun `with auto-apply on, the first edit after opening the app applies the whole route`() = runTest {
        // Restoring does not push, so the first push has to carry what was
        // restored as well as the edit - not just the edit.
        val fixture = Fixture(autoApply = true)
        fixture.open().apply {
            runCurrent()
            toggleHold(segmentIndex = 0)
            runCurrent()
        }
        val reopened = fixture.open()
        runCurrent()
        val pushes = fixture.client.pushedHolds.size
        assertFalse(connected(reopened).applied)

        reopened.toggleHold(segmentIndex = 3)
        runCurrent()

        assertTrue(connected(reopened).applied)
        assertEquals(pushes + 1, fixture.client.pushedHolds.size)
        assertEquals(mapOf(0 to "FF0000", 3 to "FF0000"), fixture.client.pushedHolds.last())
    }

    @Test
    fun `switching the wall on leaves unapplied work off the wall`() = runTest {
        val fixture = Fixture(autoApply = false)
        fixture.client.on = false
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 1)
        runCurrent()

        viewModel.toggleWall()
        runCurrent()

        assertTrue(connected(viewModel).on)
        assertTrue(fixture.client.pushedHolds.isEmpty())
    }

    @Test
    fun `the setting reaches the screen, and changing it is saved`() = runTest {
        val fixture = Fixture(autoApply = true)
        val viewModel = fixture.open()
        runCurrent()
        assertTrue(connected(viewModel).autoApply)

        viewModel.setAutoApply(false)
        runCurrent()

        assertFalse(fixture.settings.autoApplyEnabled)
        assertFalse(connected(viewModel).autoApply)
    }

    @Test
    fun `turning auto-apply off keeps an applied wall following`() = runTest {
        // Switching the setting is not opening different work, so it is no
        // reason to stop following the work that is open.
        val fixture = Fixture(autoApply = true)
        val viewModel = fixture.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()

        viewModel.setAutoApply(false)
        runCurrent()
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        assertEquals(mapOf(0 to "FF0000", 3 to "FF0000"), fixture.client.pushedHolds.last())
    }
}
