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
import com.wledclimb.app.grid.Wall
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * The wall with no controller in reach: opened from storage, edited, saved,
 * and brought back when the controller answers again.
 *
 * The fake controller serves a 2x2 wall, so segment indices run 0..3 and
 * index 3 is position (1,1).
 */
class WallOfflineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Storage that outlives any one ViewModel - the app being reopened. */
    private class Stores {
        val client = FakeWledClient(on = true)
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()
        val settings = FakeWledSettings()

        fun open() = WallViewModel(
            client = client,
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = settings,
            controllerAddress = "http://wall.test"
        )

        /**
         * Reaches the controller once, leaving [holds] as unsaved work, then
         * loses it - so the next [open] has a remembered wall and nothing to
         * ask.
         */
        fun reachedOnceThenLost(vararg holds: Int): Long {
            val first = open()
            holds.forEach { first.toggleHold(segmentIndex = it) }
            client.failWith = IOException("connect timed out")
            return (first.uiState.value as WallUiState.Ready).wallId!!
        }
    }

    private fun ready(viewModel: WallViewModel): WallUiState.Ready =
        viewModel.uiState.value as? WallUiState.Ready
            ?: error("Expected Ready but was " + viewModel.uiState.value)

    private val unreachable = ControllerState.Offline(WallProblem.Unreachable)

    @Test
    fun `a wall reached before opens from storage when the controller cannot be`() = runTest {
        val stores = Stores()
        val wallId = stores.reachedOnceThenLost()

        val viewModel = stores.open()
        runCurrent()

        val state = ready(viewModel)
        assertEquals(unreachable, state.controller)
        assertEquals(wallId, state.wallId)
        assertEquals("Test wall", state.name)
        assertEquals(2, state.wall.width)
        assertEquals(4, state.wall.holdCount)
    }

    @Test
    fun `the work left on the wall comes back offline`() = runTest {
        val stores = Stores()
        stores.reachedOnceThenLost(0, 3)

        val viewModel = stores.open()
        runCurrent()

        assertEquals(mapOf(0 to HoldColor.Red, 3 to HoldColor.Red), ready(viewModel).litHolds)
        assertTrue(ready(viewModel).modified)
    }

    @Test
    fun `offline, holds can be edited and the draft is kept`() = runTest {
        val stores = Stores()
        val wallId = stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        val pushes = stores.client.pushedHolds.size

        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()

        assertEquals(mapOf(2 to HoldColor.Red), ready(viewModel).litHolds)
        assertEquals("0,1:0", stores.wallDao.byId(wallId)?.draftHolds)
        assertEquals(pushes, stores.client.pushedHolds.size)
        assertEquals(unreachable, ready(viewModel).controller)
    }

    @Test
    fun `offline, a route can be saved, renamed and deleted`() = runTest {
        val stores = Stores()
        stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 1)
        runCurrent()

        viewModel.saveRoute("Traverse")
        runCurrent()
        val saved = viewModel.savedRoutes.value.single()
        assertEquals("Traverse", saved.name)
        assertEquals(saved.id, ready(viewModel).selectedRouteId)
        assertFalse(ready(viewModel).modified)

        viewModel.renameRoute(saved.id, "Crimps")
        runCurrent()
        assertEquals("Crimps", viewModel.savedRoutes.value.single().name)

        viewModel.deleteRoute(saved.id)
        runCurrent()
        assertTrue(viewModel.savedRoutes.value.isEmpty())
    }

    @Test
    fun `offline, a saved route can be opened`() = runTest {
        val stores = Stores()
        val first = stores.open()
        runCurrent()
        first.toggleHold(segmentIndex = 3)
        first.saveRoute("Traverse")
        runCurrent()
        val routeId = ready(first).selectedRouteId!!
        first.newRoute()
        runCurrent()
        stores.client.failWith = IOException("connect timed out")
        val viewModel = stores.open()
        runCurrent()

        viewModel.loadRoute(routeId)
        runCurrent()

        assertEquals(routeId, ready(viewModel).selectedRouteId)
        assertEquals(mapOf(3 to HoldColor.Red), ready(viewModel).litHolds)
    }

    @Test
    fun `offline, the wall controls do nothing`() = runTest {
        val stores = Stores()
        stores.reachedOnceThenLost(0)
        val viewModel = stores.open()
        runCurrent()
        val before = ready(viewModel)

        viewModel.toggleWall()
        viewModel.setBrightness(200)
        runCurrent()

        assertEquals(before, ready(viewModel))
        assertTrue(stores.client.setOnCalls.isEmpty())
        assertTrue(stores.client.setBrightnessCalls.isEmpty())
    }

    @Test
    fun `with nothing remembered, an unreachable controller is still an error`() = runTest {
        // A first run: there is no wall to fall back on.
        val stores = Stores()
        stores.client.failWith = IOException("connect timed out")

        val viewModel = stores.open()
        runCurrent()

        assertEquals(WallUiState.Error(WallProblem.Unreachable), viewModel.uiState.value)
    }

    @Test
    fun `a failed push goes offline and a reconnect comes back`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        stores.client.failWith = IOException("gone")
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        assertEquals(unreachable, ready(viewModel).controller)

        stores.client.failWith = null
        viewModel.reconnect()
        runCurrent()

        assertTrue(ready(viewModel).controller is ControllerState.Online)
    }

    @Test
    fun `nothing asks the controller again until someone does`() = runTest {
        // The app does not retry by itself: a wall coming back is for whoever
        // holds the device to act on.
        val stores = Stores()
        stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        stores.client.failWith = null
        val asked = stores.client.getWallCount

        advanceTimeBy(10 * 60 * 1000L)
        runCurrent()

        assertEquals(asked, stores.client.getWallCount)
        assertEquals(unreachable, ready(viewModel).controller)
    }

    @Test
    fun `asking again while already asking does not ask twice`() = runTest {
        val stores = Stores()
        stores.reachedOnceThenLost()
        val gate = CompletableDeferred<Unit>()
        var holding = false
        val slowClient = object : WledClient by stores.client {
            override suspend fun getWall(): Wall {
                if (holding) gate.await()
                return stores.client.getWall()
            }
        }
        val viewModel = WallViewModel(
            client = slowClient,
            walls = WallRepository(stores.wallDao),
            routes = RouteRepository(stores.routeDao) { 1000L },
            settings = stores.settings,
            controllerAddress = "http://wall.test"
        )
        runCurrent()
        assertEquals(unreachable, ready(viewModel).controller)
        stores.client.failWith = null
        holding = true
        val asked = stores.client.getWallCount

        viewModel.reconnect()
        runCurrent()
        assertEquals(ControllerState.Connecting, ready(viewModel).controller)
        viewModel.reconnect()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(asked + 1, stores.client.getWallCount)
        assertTrue(ready(viewModel).controller is ControllerState.Online)
    }

    @Test
    fun `reconnecting puts the work on screen on the wall`() = runTest {
        // Reconnecting is a deliberate tap, and coming back to the wall means
        // the wall shows what is on screen.
        val stores = Stores()
        stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 1)
        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()

        stores.client.failWith = null
        viewModel.reconnect()
        runCurrent()

        val state = ready(viewModel)
        assertTrue(state.controller is ControllerState.Online)
        assertEquals(mapOf(1 to HoldColor.Red, 2 to HoldColor.Red), state.litHolds)
        assertEquals(mapOf(1 to "FF0000", 2 to "FF0000"), stores.client.pushedHolds.last())
    }

    @Test
    fun `reconnecting with nothing on screen leaves the wall alone`() = runTest {
        // Nothing on screen is not a route, and sending it would clear the
        // wall of whatever someone is climbing.
        val stores = Stores()
        stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        val pushes = stores.client.pushedHolds.size

        stores.client.failWith = null
        viewModel.reconnect()
        runCurrent()

        assertTrue(ready(viewModel).controller is ControllerState.Online)
        assertEquals(pushes, stores.client.pushedHolds.size)
    }

    @Test
    fun `launching with the controller in reach sends nothing`() = runTest {
        // Unlike reconnecting, nobody asked for anything: the wall may be
        // showing someone else's route, and opening the app is not a request
        // to replace it. The first change sends the whole route.
        val stores = Stores()
        stores.open().apply {
            runCurrent()
            toggleHold(segmentIndex = 0)
            runCurrent()
        }
        val pushes = stores.client.pushedHolds.size

        val reopened = stores.open()
        runCurrent()
        assertEquals(pushes, stores.client.pushedHolds.size)

        reopened.toggleHold(segmentIndex = 3)
        runCurrent()
        assertEquals(mapOf(0 to "FF0000", 3 to "FF0000"), stores.client.pushedHolds.last())
    }

    @Test
    fun `a different controller at the address opens its own wall`() = runTest {
        // The MAC decides, not the address. What was built offline stays with
        // the wall it was built on.
        val stores = Stores()
        val originalId = stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        stores.client.failWith = null
        stores.client.mac = "a1b2c3d4e5f6"
        stores.client.name = "Another wall"
        viewModel.reconnect()
        runCurrent()

        val state = ready(viewModel)
        assertNotEquals(originalId, state.wallId)
        assertEquals("Another wall", state.name)
        assertTrue(state.litHolds.isEmpty())
        assertEquals("1,1:0", stores.wallDao.byId(originalId)?.draftHolds)
        assertEquals(state.wallId, stores.settings.savedWallId)
    }

    @Test
    fun `a wall that changed shape while away keeps its holds where they were`() = runTest {
        // Holds are carried by position. By segment index, widening the wall
        // would move every hold below the first row.
        val stores = Stores()
        stores.reachedOnceThenLost()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 3) // (1,1) on a 2-wide wall
        runCurrent()

        stores.client.failWith = null
        stores.client.config = """
            {"hw":{"led":{"matrix":{"panels":[
                {"b":false,"r":false,"v":false,"s":false,"x":0,"y":0,"h":2,"w":3}
            ]}}}}
        """
        viewModel.reconnect()
        runCurrent()

        val state = ready(viewModel)
        assertEquals(3, state.wall.width)
        assertEquals(mapOf(state.wall.segmentIndexAt(x = 1, y = 1) to HoldColor.Red), state.litHolds)
    }
}
