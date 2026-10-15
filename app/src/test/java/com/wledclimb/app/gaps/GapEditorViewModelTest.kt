// Same reason as the other ViewModel tests here: driving coroutines through a
// test dispatcher means the whole of kotlinx-coroutines-test, all experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.wledclimb.app.gaps

import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.ONE_DIMENSIONAL_CONFIG
import com.wledclimb.app.grid.GapCell
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.network.WledStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * The fake controller serves one 2x2 panel, so positions run (0,0) to (1,1).
 */
class GapEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun editing(viewModel: GapEditorViewModel): GapEditorUiState.Editing =
        viewModel.uiState.value as? GapEditorUiState.Editing
            ?: error("Expected Editing but was ${viewModel.uiState.value}")

    @Test
    fun `opens on the controller's gap file`() = runTest {
        val viewModel = GapEditorViewModel(FakeWledClient(gaps = "[1,0,-1,1]"))

        val state = editing(viewModel)
        assertEquals(GapCell.Unused, state.layout.cellAt(1, 0))
        assertEquals(GapCell.NoLed, state.layout.cellAt(0, 1))
        assertFalse(state.modified)
    }

    @Test
    fun `a controller out of reach leaves nothing to edit`() = runTest {
        val viewModel = GapEditorViewModel(FakeWledClient(failWith = IOException("no route to host")))

        assertEquals(
            GapEditorUiState.LoadFailed(GapEditorProblem.Unreachable),
            viewModel.uiState.value
        )
    }

    @Test
    fun `a controller that is not a matrix says so`() = runTest {
        val viewModel = GapEditorViewModel(FakeWledClient(config = ONE_DIMENSIONAL_CONFIG))

        assertEquals(
            GapEditorUiState.LoadFailed(GapEditorProblem.NotAWledMatrix),
            viewModel.uiState.value
        )
    }

    @Test
    fun `trying again after a failed read opens the editor`() = runTest {
        val client = FakeWledClient(failWith = IOException("no route to host"))
        val viewModel = GapEditorViewModel(client)

        client.failWith = null
        viewModel.load()

        editing(viewModel)
    }

    @Test
    fun `tapping paints a position with the brush`() = runTest {
        val viewModel = GapEditorViewModel(FakeWledClient())

        viewModel.selectBrush(GapCell.NoLed)
        viewModel.paint(x = 1, y = 0)

        val state = editing(viewModel)
        assertEquals(GapCell.NoLed, state.layout.cellAt(1, 0))
        assertTrue(state.modified)
    }

    @Test
    fun `tapping a hold with the hold brush turns it into an unused LED`() = runTest {
        // The same gesture as turning a hold off in a route. Unused rather
        // than no LED, because claiming a wired LED is missing shifts every
        // LED after it.
        val viewModel = GapEditorViewModel(FakeWledClient())

        viewModel.paint(x = 0, y = 0)

        assertEquals(GapCell.Unused, editing(viewModel).layout.cellAt(0, 0))
    }

    @Test
    fun `tapping it again makes it a hold again`() = runTest {
        val viewModel = GapEditorViewModel(FakeWledClient())

        viewModel.paint(x = 0, y = 0)
        viewModel.paint(x = 0, y = 0)

        val state = editing(viewModel)
        assertEquals(GapCell.Hold, state.layout.cellAt(0, 0))
        assertFalse("back where it started is not an edit", state.modified)
    }

    @Test
    fun `reset goes back to what the controller has`() = runTest {
        val viewModel = GapEditorViewModel(FakeWledClient())
        viewModel.paint(x = 0, y = 0)

        viewModel.reset()

        assertFalse(editing(viewModel).modified)
    }

    @Test
    fun `saving uploads the layout, restarts the controller and finishes`() = runTest {
        val client = FakeWledClient()
        val viewModel = GapEditorViewModel(client)
        viewModel.selectBrush(GapCell.NoLed)
        viewModel.paint(x = 1, y = 1)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(GapEditorUiState.Saved, viewModel.uiState.value)
        assertEquals(GapCell.NoLed, client.savedGapLayouts.single().cellAt(1, 1))
        assertEquals(1, client.restartCount)
    }

    @Test
    fun `it waits for the controller to come back before finishing`() = runTest {
        // The wall is read again as soon as this finishes, and a controller
        // still booting would read as out of reach.
        val client = FakeWledClient()
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)

        viewModel.save()

        assertEquals(GapEditorUiState.Restarting, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(GapEditorUiState.Saved, viewModel.uiState.value)
    }

    @Test
    fun `a controller slow to come back is waited for, up to a point`() = runTest {
        val client = object : WledClient by FakeWledClient() {
            override suspend fun getStatus(): WledStatus = throw IOException("still booting")
        }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)

        viewModel.save()
        advanceUntilIdle()

        // The layout is saved whatever happens; the wall says the controller
        // is out of reach, with the usual way to try again.
        assertEquals(GapEditorUiState.Saved, viewModel.uiState.value)
    }

    @Test
    fun `nothing to save is not saved`() = runTest {
        val client = FakeWledClient()
        val viewModel = GapEditorViewModel(client)

        viewModel.save()
        advanceUntilIdle()

        assertTrue(client.savedGapLayouts.isEmpty())
        assertEquals(0, client.restartCount)
    }

    @Test
    fun `a PIN-locked controller asks for its PIN`() = runTest {
        val client = FakeWledClient().apply { settingsPin = "1234" }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)

        viewModel.save()
        advanceUntilIdle()

        val state = editing(viewModel)
        assertEquals(PinRequest.Needed, state.pin)
        assertTrue("the edits should still be there", state.modified)
        assertFalse(state.saving)
        assertEquals("nothing was written, so nothing restarts", 0, client.restartCount)
    }

    @Test
    fun `the right PIN unlocks the controller and saves`() = runTest {
        val client = FakeWledClient().apply { settingsPin = "1234" }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)
        viewModel.save()
        advanceUntilIdle()

        viewModel.saveWithPin("1234")
        advanceUntilIdle()

        assertEquals(GapEditorUiState.Saved, viewModel.uiState.value)
        assertEquals(1, client.savedGapLayouts.size)
        assertEquals(1, client.restartCount)
    }

    @Test
    fun `a wrong PIN is said to be wrong, and asked for again`() = runTest {
        val client = FakeWledClient().apply { settingsPin = "1234" }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)
        viewModel.save()
        advanceUntilIdle()

        viewModel.saveWithPin("9999")
        advanceUntilIdle()

        val state = editing(viewModel)
        assertEquals(PinRequest.Rejected, state.pin)
        assertFalse(state.saving)
        assertTrue(client.savedGapLayouts.isEmpty())
    }

    @Test
    fun `the right PIN after a wrong one waits out WLED's lockout first`() = runTest {
        // WLED refuses every PIN for 3s after a wrong one, the right one
        // included. Sent straight away, a corrected PIN would be reported as
        // wrong too.
        val client = FakeWledClient().apply { settingsPin = "1234" }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)
        viewModel.save()
        advanceUntilIdle()
        viewModel.saveWithPin("9999")
        advanceUntilIdle()

        viewModel.saveWithPin("1234")
        runCurrent()
        assertEquals("not offered yet", listOf("9999"), client.offeredPins)
        assertTrue("shown as checking meanwhile", editing(viewModel).saving)

        advanceUntilIdle()
        assertEquals(listOf("9999", "1234"), client.offeredPins)
        assertEquals(GapEditorUiState.Saved, viewModel.uiState.value)
    }

    @Test
    fun `giving up on the PIN keeps the edits and says why nothing was saved`() = runTest {
        val client = FakeWledClient().apply { settingsPin = "1234" }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)
        viewModel.save()
        advanceUntilIdle()

        viewModel.cancelPin()

        val state = editing(viewModel)
        assertNull(state.pin)
        assertEquals(GapEditorProblem.Locked, state.problem)
        assertTrue(state.modified)
    }

    @Test
    fun `losing the controller while unlocking says so rather than blaming the PIN`() = runTest {
        val client = FakeWledClient().apply { settingsPin = "1234" }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)
        viewModel.save()
        advanceUntilIdle()

        client.failWith = IOException("no route to host")
        viewModel.saveWithPin("1234")
        advanceUntilIdle()

        val state = editing(viewModel)
        assertNull(state.pin)
        assertEquals(GapEditorProblem.Unreachable, state.problem)
    }

    @Test
    fun `a failed restart can be retried by saving again`() = runTest {
        // The file is written but the controller is still drawing the old
        // layout, so it still counts as unsaved.
        val client = FakeWledClient().apply { failRestartWith = IOException("dropped") }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)

        viewModel.save()
        advanceUntilIdle()
        val failed = editing(viewModel)
        assertEquals(GapEditorProblem.NotRestarted, failed.problem)
        assertTrue(failed.modified)

        client.failRestartWith = null
        viewModel.save()
        advanceUntilIdle()
        assertEquals(GapEditorUiState.Saved, viewModel.uiState.value)
        assertEquals(1, client.restartCount)
    }

    @Test
    fun `the next edit clears the last problem`() = runTest {
        val client = FakeWledClient().apply { failSaveWith = IOException("dropped") }
        val viewModel = GapEditorViewModel(client)
        viewModel.paint(x = 0, y = 0)
        viewModel.save()
        advanceUntilIdle()

        viewModel.paint(x = 1, y = 0)

        assertNull(editing(viewModel).problem)
    }

    @Test
    fun `positions no panel covers cannot be painted`() = runTest {
        // Two 1x1 panels on a diagonal: (1,0) and (0,1) have nothing behind
        // them, and the gap file is not what decides that.
        val diagonal = """{"hw":{"led":{"matrix":{"panels":[
            {"b":false,"r":false,"v":false,"s":false,"x":0,"y":0,"h":1,"w":1},
            {"b":false,"r":false,"v":false,"s":false,"x":1,"y":1,"h":1,"w":1}
        ]}}}}"""
        val viewModel = GapEditorViewModel(FakeWledClient(config = diagonal))

        viewModel.paint(x = 1, y = 0)

        val state = editing(viewModel)
        assertNull(state.layout.cellAt(1, 0))
        assertFalse(state.modified)
    }
}
