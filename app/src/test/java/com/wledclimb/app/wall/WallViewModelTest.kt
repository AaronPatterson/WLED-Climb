// Every test here drives coroutines through a test dispatcher, and the whole
// of kotlinx-coroutines-test is marked experimental - runTest, runCurrent,
// UnconfinedTestDispatcher, setMain. There is no non-experimental way to write
// these tests, so the opt-in is an acknowledgement rather than a choice, and
// it belongs at the top of the file rather than repeated at every call site.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.wall

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.wledclimb.app.palette.HoldColor
import com.wledclimb.app.network.WledStatus
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.storage.InMemoryRouteDao
import com.wledclimb.app.storage.InMemoryWallDao
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository
import com.wledclimb.app.ONE_DIMENSIONAL_CONFIG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class WallViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Each test gets its own empty wall store. None of them assert on it - the
     * repository has its own tests - but the ViewModel now needs one to connect.
     */
    private fun wallViewModel(client: WledClient) = WallViewModel(
        client = client,
        walls = WallRepository(InMemoryWallDao()),
        routes = RouteRepository(InMemoryRouteDao()),
        settings = FakeWledSettings(),
        controllerAddress = "http://wall.test"
    )

    private fun connectedState(viewModel: WallViewModel): WallUiState.Ready =
        viewModel.uiState.value as? WallUiState.Ready
            ?: error("Expected Ready but was ${viewModel.uiState.value}")

    @Test
    fun `loads power state and grid layout on creation`() = runTest {
        val viewModel = wallViewModel(FakeWledClient(on = true))

        val state = connectedState(viewModel)
        assertTrue(state.online.on)
        assertEquals(2, state.wall.width)
        assertEquals(2, state.wall.height)
        assertTrue(state.wall.hasHoldAt(x = 0, y = 0))
        assertTrue(state.wall.hasHoldAt(x = 1, y = 1))
    }

    @Test
    fun `applies the gap file to the grid when the controller has one`() = runTest {
        // Second cell has no LED behind it, so there is no hold to light there.
        val viewModel = wallViewModel(FakeWledClient(gaps = "[1,-1,1,1]"))

        val wall = connectedState(viewModel).wall
        assertTrue(wall.hasHoldAt(x = 0, y = 0))
        assertFalse(wall.hasHoldAt(x = 1, y = 0))
        assertTrue(wall.hasHoldAt(x = 0, y = 1))
        assertEquals(3, wall.holdCount)
    }

    @Test
    fun `an unchanged brightness is not sent again`() = runTest {
        // A drag reports per frame and truncates to an Int, so the same value
        // arrives several times in a row. Each repeat would rebuild the state
        // and push another request for a wall already showing it.
        val client = FakeWledClient(on = true, brightness = 128)
        val viewModel = wallViewModel(client)

        viewModel.setBrightness(200)
        runCurrent()
        assertEquals(listOf(200 to true), client.setBrightnessCalls)

        viewModel.setBrightness(200)
        viewModel.setBrightness(200)
        runCurrent()
        assertEquals(listOf(200 to true), client.setBrightnessCalls)

        // A value that really did change still goes out.
        viewModel.setBrightness(201)
        runCurrent()
        assertEquals(listOf(200 to true, 201 to true), client.setBrightnessCalls)
    }

    @Test
    fun `a late confirmation does not drag brightness back`() = runTest {
        // The reported bug: the slider stuttered mid-drag, and at the top of the
        // range became unmovable. Requests are conflated, so a reply can confirm
        // a value the finger has already left. Writing that reply into state
        // pulled the slider backwards - and at maximum it did so faster than a
        // drag could move away, which reads as the control being stuck.
        //
        // Both replies are held open. Releasing only the first leaves the newer
        // request still in flight, which is the one moment the stale value could
        // win - let the newer one finish and state converges either way, which
        // is how an earlier version of this test managed to pass against the bug
        // it was written for.
        val firstReply = CompletableDeferred<Unit>()
        val secondReply = CompletableDeferred<Unit>()
        val client = object : WledClient by FakeWledClient(on = true) {
            override suspend fun setBrightness(brightness: Int, on: Boolean): WledStatus {
                if (brightness == 100) firstReply.await() else secondReply.await()
                return WledStatus(on = on, brightness = brightness)
            }
        }
        val viewModel = wallViewModel(client)
        connectedState(viewModel)

        viewModel.setBrightness(100)
        runCurrent()
        viewModel.setBrightness(240)
        runCurrent()
        firstReply.complete(Unit)
        runCurrent()

        val state = viewModel.uiState.value as WallUiState.Ready
        assertEquals("the newer value should survive the older reply", 240, state.online.brightness)
    }

    @Test
    fun `a failed brightness change does not tear down the screen`() = runTest {
        // The reported bug: dragging the brightness slider dropped the whole
        // screen to "couldn't reach the wall", losing the grid and the route.
        // A dropped brightness request is not evidence the wall has gone - it
        // is one request among many during a drag.
        val client = FakeWledClient(on = true)
        val viewModel = wallViewModel(client)
        val before = connectedState(viewModel)
        viewModel.toggleHold(before.wall.segmentIndexAt(x = 0, y = 0))
        runCurrent()

        client.failWith = IOException("unexpected end of stream")
        viewModel.setBrightness(200)
        runCurrent()

        val after = viewModel.uiState.value
        assertTrue("expected to stay connected, was $after", after is WallUiState.Ready)
        assertEquals(
            "the route should survive a failed brightness change",
            1,
            (after as WallUiState.Ready).litHolds.size
        )
    }

    @Test
    fun `toggling flips the wall and keeps the grid`() = runTest {
        val client = FakeWledClient(on = false)
        val viewModel = wallViewModel(client)
        val wallBefore = connectedState(viewModel).wall

        viewModel.toggleWall()

        val state = connectedState(viewModel)
        assertTrue(state.online.on)
        assertEquals(listOf(true), client.setOnCalls)
        // The grid comes from /json/cfg, which a toggle doesn't re-read - it has
        // to be carried across or the grid would vanish on every tap.
        assertEquals(wallBefore, state.wall)
        assertEquals(false, state.busy)
    }

    @Test
    fun `toggling does nothing when not connected`() = runTest {
        val client = FakeWledClient(failWith = IOException("boom"))
        val viewModel = wallViewModel(client)

        viewModel.toggleWall()

        assertTrue(viewModel.uiState.value is WallUiState.Error)
        assertEquals(emptyList<Boolean>(), client.setOnCalls)
    }

    @Test
    fun `an unreachable controller reports a network problem`() = runTest {
        val viewModel = wallViewModel(FakeWledClient(failWith = IOException("connect timed out")))

        assertEquals(WallUiState.Error(WallProblem.Unreachable), viewModel.uiState.value)
    }

    @Test
    fun `a controller that isn't a 2D matrix reports a config problem, not a network one`() = runTest {
        val viewModel = wallViewModel(FakeWledClient(config = ONE_DIMENSIONAL_CONFIG))

        // The controller answered fine - reporting this as a network problem
        // would send the user off debugging the wrong thing entirely.
        assertEquals(WallUiState.Error(WallProblem.NotAWledMatrix), viewModel.uiState.value)
    }

    @Test
    fun `retrying after a failure reconnects`() = runTest {
        val client = FakeWledClient(failWith = IOException("down"))
        val viewModel = wallViewModel(client)
        assertTrue(viewModel.uiState.value is WallUiState.Error)

        client.failWith = null
        viewModel.refresh()

        assertTrue(viewModel.uiState.value is WallUiState.Ready)
    }

    @Test
    fun `tapping a hold lights it and pushes the route`() = runTest {
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)

        viewModel.toggleHold(segmentIndex = 2)

        assertEquals(mapOf(2 to HoldColor.Red), connectedState(viewModel).litHolds)
        assertEquals(listOf(mapOf(2 to "FF0000")), client.pushedHolds)
        // The clear range has to cover the whole 2x2 segment buffer, or holds
        // outside it stay lit.
        assertEquals(4, client.lastPixelCount)
    }

    @Test
    fun `tapping a lit hold clears it`() = runTest {
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)
        viewModel.toggleHold(segmentIndex = 2)

        viewModel.toggleHold(segmentIndex = 2)

        assertEquals(emptyMap<Int, HoldColor>(), connectedState(viewModel).litHolds)
        assertEquals(emptyMap<Int, String>(), client.pushedHolds.last())
    }

    @Test
    fun `each push carries the whole route, not just the hold that changed`() = runTest {
        // WLED keeps previously set pixels, so an incremental push would leave
        // a cleared hold lit on the wall.
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)

        viewModel.toggleHold(segmentIndex = 0)
        viewModel.toggleHold(segmentIndex = 3)

        assertEquals(mapOf(0 to "FF0000", 3 to "FF0000"), client.pushedHolds.last())
    }

    @Test
    fun `powering the wall off keeps the route in the app`() = runTest {
        val viewModel = wallViewModel(FakeWledClient(on = true))
        viewModel.toggleHold(segmentIndex = 1)

        viewModel.toggleWall()

        val state = connectedState(viewModel)
        assertEquals(false, state.online.on)
        assertEquals(mapOf(1 to HoldColor.Red), state.litHolds)
    }

    @Test
    fun `switching the wall back on re-pushes the route`() = runTest {
        // WLED unfreezes every segment when it's switched on, dropping the
        // per-pixel route - without re-pushing, the app would still show a
        // route the wall had already forgotten.
        val client = FakeWledClient(on = false)
        val viewModel = wallViewModel(client)
        viewModel.toggleHold(segmentIndex = 1)
        val pushesBefore = client.pushedHolds.size

        viewModel.toggleWall()

        assertEquals(true, connectedState(viewModel).online.on)
        assertEquals(pushesBefore + 1, client.pushedHolds.size)
        assertEquals(mapOf(1 to "FF0000"), client.pushedHolds.last())
    }

    @Test
    fun `switching the wall on with no route pushes nothing`() = runTest {
        val client = FakeWledClient(on = false)
        val viewModel = wallViewModel(client)

        viewModel.toggleWall()

        assertEquals(emptyList<Map<Int, String>>(), client.pushedHolds)
    }

    @Test
    fun `a failed push marks the wall out of reach and keeps the work`() = runTest {
        // Rather than leaving the app and the wall silently disagreeing - and
        // rather than taking the grid away, which it used to.
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)
        client.failWith = IOException("gone")

        viewModel.toggleHold(segmentIndex = 1)

        val state = connectedState(viewModel)
        assertEquals(ControllerState.Offline(WallProblem.Unreachable), state.controller)
        assertEquals(mapOf(1 to HoldColor.Red), state.litHolds)
        assertFalse(state.applied)
    }

    @Test
    fun `tapping a hold does nothing when not connected`() = runTest {
        val client = FakeWledClient(failWith = IOException("down"))
        val viewModel = wallViewModel(client)

        viewModel.toggleHold(segmentIndex = 1)

        assertEquals(emptyList<Map<Int, String>>(), client.pushedHolds)
    }

    @Test
    fun `holds are painted in the selected colour`() = runTest {
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)

        viewModel.selectColor(HoldColor.Blue)
        viewModel.toggleHold(segmentIndex = 1)

        assertEquals(mapOf(1 to HoldColor.Blue), connectedState(viewModel).litHolds)
        // The wire format is WLED's hex, not the enum.
        assertEquals(mapOf(1 to "0000FF"), client.pushedHolds.last())
    }

    @Test
    fun `tapping a hold already in the selected colour clears it`() = runTest {
        val viewModel = wallViewModel(FakeWledClient())
        viewModel.selectColor(HoldColor.Green)
        viewModel.toggleHold(segmentIndex = 1)

        viewModel.toggleHold(segmentIndex = 1)

        assertEquals(emptyMap<Int, HoldColor>(), connectedState(viewModel).litHolds)
    }

    @Test
    fun `tapping a hold showing a different colour repaints it`() = runTest {
        // Repaint rather than clear: needing to erase before recolouring would
        // be a fiddly extra step for a six-year-old.
        val viewModel = wallViewModel(FakeWledClient())
        viewModel.selectColor(HoldColor.Green)
        viewModel.toggleHold(segmentIndex = 1)

        viewModel.selectColor(HoldColor.Purple)
        viewModel.toggleHold(segmentIndex = 1)

        assertEquals(mapOf(1 to HoldColor.Purple), connectedState(viewModel).litHolds)
    }

    @Test
    fun `changing colour leaves holds already on the wall alone`() = runTest {
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)
        viewModel.toggleHold(segmentIndex = 1)
        val pushesBefore = client.pushedHolds.size

        viewModel.selectColor(HoldColor.Yellow)

        assertEquals(mapOf(1 to HoldColor.Red), connectedState(viewModel).litHolds)
        assertEquals(pushesBefore, client.pushedHolds.size)
    }

    @Test
    fun `clearing turns every hold off in one push`() = runTest {
        // The point of this over tapping each hold: one request, not one per hold.
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)
        viewModel.toggleHold(segmentIndex = 0)
        viewModel.toggleHold(segmentIndex = 3)
        val pushesBefore = client.pushedHolds.size

        viewModel.clearWall()

        assertEquals(emptyMap<Int, HoldColor>(), connectedState(viewModel).litHolds)
        assertEquals(pushesBefore + 1, client.pushedHolds.size)
        assertEquals(emptyMap<Int, String>(), client.pushedHolds.last())
    }

    @Test
    fun `clearing an already empty wall does nothing`() = runTest {
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)

        viewModel.clearWall()

        assertEquals(emptyList<Map<Int, String>>(), client.pushedHolds)
    }

    @Test
    fun `a failed clear marks the wall out of reach`() = runTest {
        val client = FakeWledClient()
        val viewModel = wallViewModel(client)
        viewModel.toggleHold(segmentIndex = 1)
        client.failWith = IOException("gone")

        viewModel.clearWall()

        assertEquals(
            ControllerState.Offline(WallProblem.Unreachable),
            connectedState(viewModel).controller
        )
    }
}
