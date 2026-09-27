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
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.network.WledStatus
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Working offline by choice: the controller in reach and left alone, so a
 * route can be built while someone climbs the one on the wall.
 *
 * The fake controller serves a 2x2 wall, so segment indices run 0..3.
 */
class WallPauseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Storage that outlives any one ViewModel - the app being reopened. */
    private class Stores {
        val client = FakeWledClient(on = true)
        val wallDao = InMemoryWallDao()
        val routeDao = InMemoryRouteDao()
        val settings = FakeWledSettings()

        fun open(client: WledClient = this.client) = WallViewModel(
            client = client,
            walls = WallRepository(wallDao),
            routes = RouteRepository(routeDao) { 1000L },
            settings = settings,
            controllerAddress = "http://wall.test"
        )
    }

    private fun ready(viewModel: WallViewModel): WallUiState.Ready =
        viewModel.uiState.value as? WallUiState.Ready
            ?: error("Expected Ready but was " + viewModel.uiState.value)

    @Test
    fun `paused, edits stay on this device`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.toggleHold(segmentIndex = 0)
        runCurrent()
        val pushes = stores.client.pushedHolds.size

        viewModel.pause()
        viewModel.toggleHold(segmentIndex = 3)
        runCurrent()

        assertEquals(ControllerState.Paused, ready(viewModel).controller)
        assertEquals(mapOf(0 to HoldColor.Red, 3 to HoldColor.Red), ready(viewModel).litHolds)
        assertEquals(pushes, stores.client.pushedHolds.size)
        assertEquals("0,0:0;1,1:0", stores.wallDao.byId(ready(viewModel).wallId!!)?.draftHolds)
    }

    @Test
    fun `paused, the wall controls do nothing`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.pause()
        runCurrent()

        viewModel.toggleWall()
        viewModel.setBrightness(200)
        runCurrent()

        assertTrue(stores.client.setOnCalls.isEmpty())
        assertTrue(stores.client.setBrightnessCalls.isEmpty())
        assertEquals(ControllerState.Paused, ready(viewModel).controller)
    }

    @Test
    fun `a pause survives the app being reopened, without asking the controller`() = runTest {
        // A relaunch that reconnected would put the half-built route on the
        // wall at the next tap - the one thing pausing exists to prevent.
        val stores = Stores()
        stores.open().apply {
            runCurrent()
            pause()
            toggleHold(segmentIndex = 2)
            runCurrent()
        }
        assertTrue(stores.settings.isPaused)
        val asked = stores.client.getWallCount

        val reopened = stores.open()
        runCurrent()

        assertEquals(ControllerState.Paused, ready(reopened).controller)
        assertEquals(mapOf(2 to HoldColor.Red), ready(reopened).litHolds)
        assertEquals(asked, stores.client.getWallCount)
    }

    @Test
    fun `going back online puts the work on the wall and ends the pause`() = runTest {
        val stores = Stores()
        val viewModel = stores.open()
        runCurrent()
        viewModel.pause()
        viewModel.toggleHold(segmentIndex = 1)
        viewModel.toggleHold(segmentIndex = 2)
        runCurrent()

        viewModel.reconnect()
        runCurrent()

        assertTrue(ready(viewModel).controller is ControllerState.Online)
        assertEquals(mapOf(1 to "FF0000", 2 to "FF0000"), stores.client.pushedHolds.last())
        assertFalse(stores.settings.isPaused)
    }

    @Test
    fun `a wall out of reach can be paused too`() = runTest {
        // So it stays left alone when it comes back.
        val stores = Stores()
        stores.client.failWith = IOException("connect timed out")
        stores.open().apply { runCurrent() }
        // Reached once, so there is a stored wall to open.
        stores.client.failWith = null
        stores.open().apply { runCurrent() }
        stores.client.failWith = IOException("connect timed out")
        val viewModel = stores.open()
        runCurrent()
        assertTrue(ready(viewModel).controller is ControllerState.Offline)

        viewModel.pause()
        runCurrent()

        assertEquals(ControllerState.Paused, ready(viewModel).controller)
        assertTrue(stores.settings.isPaused)
    }

    @Test
    fun `nothing pauses while connecting`() = runTest {
        // The answer would arrive after the pause and undo it.
        val stores = Stores()
        val gate = CompletableDeferred<Unit>()
        val slow = object : WledClient by stores.client {
            override suspend fun getStatus(): WledStatus {
                gate.await()
                return stores.client.getStatus()
            }
        }
        stores.open().apply { runCurrent() }
        val viewModel = stores.open(slow)
        runCurrent()
        assertEquals(ControllerState.Connecting, ready(viewModel).controller)

        viewModel.pause()
        gate.complete(Unit)
        runCurrent()

        assertTrue(ready(viewModel).controller is ControllerState.Online)
        assertFalse(stores.settings.isPaused)
    }

    @Test
    fun `a power change answered after pausing does not undo the pause`() = runTest {
        val stores = Stores()
        val gate = CompletableDeferred<Unit>()
        val slow = object : WledClient by stores.client {
            override suspend fun setOn(on: Boolean): WledStatus {
                gate.await()
                return stores.client.setOn(on)
            }
        }
        val viewModel = stores.open(slow)
        runCurrent()

        viewModel.toggleWall()
        viewModel.pause()
        gate.complete(Unit)
        runCurrent()

        assertEquals(ControllerState.Paused, ready(viewModel).controller)
    }

    @Test
    fun `a push that fails after pausing does not count as out of reach`() = runTest {
        // The pause was asked for; the failure only confirms it.
        val stores = Stores()
        val gate = CompletableDeferred<Unit>()
        val slow = object : WledClient by stores.client {
            override suspend fun setHoldColors(pixelCount: Int, lit: Map<Int, String>) {
                gate.await()
                throw IOException("gone")
            }
        }
        val viewModel = stores.open(slow)
        runCurrent()

        viewModel.toggleHold(segmentIndex = 0)
        viewModel.pause()
        gate.complete(Unit)
        runCurrent()

        assertEquals(ControllerState.Paused, ready(viewModel).controller)
    }
}
