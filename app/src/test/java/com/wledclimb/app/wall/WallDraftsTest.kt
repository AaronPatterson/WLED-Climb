// Every test here drives coroutines through a test dispatcher, and the whole
// of kotlinx-coroutines-test is marked experimental - runTest, runCurrent,
// UnconfinedTestDispatcher, setMain. There is no non-experimental way to write
// these tests, so the opt-in is an acknowledgement rather than a choice, and
// it belongs at the top of the file rather than repeated at every call site.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unsaved work surviving the app being closed.
 *
 * The rule throughout: closing the app never saves anything. The route on disk
 * stays as it was, and the wall comes back looking like the edit was made a
 * moment ago.
 */
class WallDraftsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Stores {
        val client = FakeWledClient(on = true)
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()

        /** A ViewModel over the same storage - the app being opened again. */
        fun open() = WallViewModel(
            client = client,
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            controllerAddress = "http://wall.test"
        )
    }

    private fun connected(viewModel: WallViewModel): WallUiState.Connected =
        viewModel.uiState.value as? WallUiState.Connected
            ?: error("Expected Connected but was " + viewModel.uiState.value)

    @Test
    fun `an edit to a saved route comes back unsaved, not applied to the route`() = runTest {
        val stores = Stores()
        val first = stores.open()
        runCurrent()
        first.toggleHold(segmentIndex = 0)
        runCurrent()
        first.saveRoute("Traverse")
        runCurrent()
        val routeId = first.savedRoutes.value.single().id
        val saved = stores.routeDao.byId(routeId)!!.holds

        // An edit nobody saved.
        first.toggleHold(segmentIndex = 3)
        runCurrent()
        assertTrue(connected(first).modified)

        val reopened = stores.open()
        runCurrent()

        assertEquals(
            mapOf(0 to HoldColor.Red, 3 to HoldColor.Red),
            connected(reopened).litHolds
        )
        assertTrue(connected(reopened).modified)
        assertEquals(routeId, connected(reopened).selectedRouteId)
        // The route itself is untouched. Closing the app saves nothing.
        assertEquals(saved, stores.routeDao.byId(routeId)!!.holds)
    }

    @Test
    fun `work with no route behind it survives too`() = runTest {
        // The first route anyone builds belongs to nothing, and deserves to
        // survive an interruption just as much as an edit to a saved one.
        val stores = Stores()
        val first = stores.open()
        runCurrent()
        first.selectColor(HoldColor.Blue)
        first.toggleHold(segmentIndex = 2)
        runCurrent()

        val reopened = stores.open()
        runCurrent()

        assertEquals(mapOf(2 to HoldColor.Blue), connected(reopened).litHolds)
        assertTrue(connected(reopened).modified)
        assertNull(connected(reopened).selectedRouteId)
        assertTrue(reopened.savedRoutes.value.isEmpty())
    }

    @Test
    fun `undoing an edit stops the wall looking modified`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        viewModel.saveRoute("Traverse")
        runCurrent()

        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()
        assertTrue(connected(viewModel).modified)

        // Tapping it again puts the hold back out.
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        assertFalse(connected(viewModel).modified)
        assertNull(stores.wallDao.byId(connected(viewModel).wallId!!)?.draftHolds)
    }

    @Test
    fun `saving makes the wall the saved state again`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        assertTrue(connected(viewModel).modified)

        viewModel.saveRoute("Traverse")
        runCurrent()

        assertFalse(connected(viewModel).modified)
        assertNull(stores.wallDao.byId(connected(viewModel).wallId!!)?.draftHolds)
    }

    @Test
    fun `opening a route is not an edit of it`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        viewModel.saveRoute("Traverse")
        runCurrent()
        val routeId = viewModel.savedRoutes.value.single().id

        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()
        viewModel.loadRoute(routeId)
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red), connected(viewModel).litHolds)
        assertFalse(connected(viewModel).modified)
    }

    @Test
    fun `starting a new route closes the one that was open`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        viewModel.saveRoute("Traverse")
        runCurrent()

        viewModel.newRoute()
        runCurrent()

        assertTrue(connected(viewModel).litHolds.isEmpty())
        assertNull(connected(viewModel).selectedRouteId)
        // A blank wall belonging to no route is not a draft of anything.
        assertFalse(connected(viewModel).modified)
        assertEquals(1, viewModel.savedRoutes.value.size)
    }

    @Test
    fun `a new route does not survive as a draft until something is drawn`() = runTest {
        val stores = Stores()
        val first = stores.open()
        runCurrent()
        first.newRoute()
        runCurrent()

        val reopened = stores.open()
        runCurrent()

        assertTrue(connected(reopened).litHolds.isEmpty())
        assertFalse(connected(reopened).modified)
    }

    @Test
    fun `resetting brings back the route as saved`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        viewModel.saveRoute("Traverse")
        runCurrent()

        viewModel.toggleHold(segmentIndex = 3)
        viewModel.toggleHold(segmentIndex = 1)
        runCurrent()
        assertTrue(connected(viewModel).modified)

        viewModel.revertRoute()
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red), connected(viewModel).litHolds)
        assertFalse(connected(viewModel).modified)
        assertNull(stores.wallDao.byId(connected(viewModel).wallId!!)?.draftHolds)
    }

    @Test
    fun `the reset route is pushed to the wall, not just shown`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        viewModel.saveRoute("Traverse")
        runCurrent()
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        viewModel.revertRoute()
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red.hex), stores.client.pushedHolds.last())
    }

    @Test
    fun `resetting work with no route behind it clears the wall`() = runTest {
        // There is no saved state to come back to, so reverting a draft that
        // belongs to nothing means going back to nothing.
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()
        assertTrue(connected(viewModel).modified)

        viewModel.revertRoute()
        runCurrent()

        assertTrue(connected(viewModel).litHolds.isEmpty())
        assertFalse(connected(viewModel).modified)
        assertNull(connected(viewModel).selectedRouteId)
    }

    @Test
    fun `resetting an unmodified route does nothing`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        viewModel.saveRoute("Traverse")
        runCurrent()
        val pushes = stores.client.pushedHolds.size

        viewModel.revertRoute()
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red), connected(viewModel).litHolds)
        assertEquals(pushes, stores.client.pushedHolds.size)
    }

    @Test
    fun `a reset survives closing the app`() = runTest {
        // The draft has to be gone from storage, not just from the screen.
        val stores = Stores()
        val first = stores.open()
        runCurrent()
        first.toggleHold(segmentIndex = 0)
        runCurrent()
        first.saveRoute("Traverse")
        runCurrent()
        first.toggleHold(segmentIndex = 3)
        runCurrent()
        first.revertRoute()
        runCurrent()

        val reopened = stores.open()
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red), connected(reopened).litHolds)
        assertFalse(connected(reopened).modified)
    }
}
