package com.wledclimb.app.setup

import com.wledclimb.app.FakeWledClient
import com.wledclimb.app.FakeWledSettings
import com.wledclimb.app.MainDispatcherRule
import com.wledclimb.app.ONE_DIMENSIONAL_CONFIG
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class SetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeWledSettings()
    private val client = FakeWledClient()
    private val requestedBaseUrls = mutableListOf<String>()

    private fun viewModel() = SetupViewModel(
        settings = settings,
        clientFactory = { baseUrl ->
            requestedBaseUrls += baseUrl
            client
        }
    )

    private fun editingState(viewModel: SetupViewModel): SetupUiState.Editing =
        viewModel.uiState.value as? SetupUiState.Editing
            ?: error("Expected Editing but was ${viewModel.uiState.value}")

    @Test
    fun `a working controller is saved and setup completes`() = runTest {
        val viewModel = viewModel()
        viewModel.onIpInputChange("192.168.1.50")

        viewModel.testAndSave()

        assertEquals(SetupUiState.Connected(ip = "http://192.168.1.50"), viewModel.uiState.value)
        assertEquals("http://192.168.1.50", settings.savedIp)
    }

    @Test
    fun `a new address keeps the remembered wall`() = runTest {
        // Only a controller answering as a different wall replaces it. A new
        // address is often the same controller on a new lease, and until
        // something answers, the last wall is still the one to open offline.
        val settings = FakeWledSettings(initialIp = "http://192.168.1.50", initialWallId = 7L)
        val viewModel = SetupViewModel(settings = settings, clientFactory = { client })
        viewModel.onIpInputChange("192.168.1.60")

        viewModel.testAndSave()

        assertEquals("http://192.168.1.60", settings.savedIp)
        assertEquals(7L, settings.savedWallId)
    }

    @Test
    fun `a bare address gets an http prefix, an explicit one is left alone`() = runTest {
        viewModel().apply {
            onIpInputChange("192.168.1.50")
            testAndSave()
        }
        viewModel().apply {
            onIpInputChange("https://wled.local")
            testAndSave()
        }

        assertEquals(listOf("http://192.168.1.50", "https://wled.local"), requestedBaseUrls)
    }

    @Test
    fun `surrounding whitespace is trimmed off the address`() = runTest {
        val viewModel = viewModel()
        viewModel.onIpInputChange("  192.168.1.50  ")

        viewModel.testAndSave()

        assertEquals(listOf("http://192.168.1.50"), requestedBaseUrls)
    }

    @Test
    fun `an empty address is rejected without contacting anything`() = runTest {
        val viewModel = viewModel()
        viewModel.onIpInputChange("   ")

        viewModel.testAndSave()

        assertEquals(SetupProblem.EmptyAddress, editingState(viewModel).problem)
        assertEquals(emptyList<String>(), requestedBaseUrls)
        assertNull(settings.savedIp)
    }

    @Test
    fun `an unreachable address reports a network problem and saves nothing`() = runTest {
        client.failWith = IOException("connect timed out")
        val viewModel = viewModel()
        viewModel.onIpInputChange("192.168.1.99")

        viewModel.testAndSave()

        assertEquals(SetupProblem.Unreachable("192.168.1.99"), editingState(viewModel).problem)
        assertNull(settings.savedIp)
    }

    @Test
    fun `a device that answers but isn't a WLED matrix is rejected, not saved`() = runTest {
        // Regression guard: setup used to accept anything that returned HTTP 200,
        // so a router's web UI would "pass" and only fail on the next screen -
        // by which point the address field is gone.
        client.config = ONE_DIMENSIONAL_CONFIG
        val viewModel = viewModel()
        viewModel.onIpInputChange("192.168.1.1")

        viewModel.testAndSave()

        assertEquals(SetupProblem.NotAWledMatrix("192.168.1.1"), editingState(viewModel).problem)
        assertNull(settings.savedIp)
    }

    @Test
    fun `a 2D config with no panels is rejected`() = runTest {
        client.config = """{"hw":{"led":{"matrix":{"panels":[]}}}}"""
        val viewModel = viewModel()
        viewModel.onIpInputChange("192.168.1.50")

        viewModel.testAndSave()

        assertEquals(SetupProblem.NotAWledMatrix("192.168.1.50"), editingState(viewModel).problem)
        assertNull(settings.savedIp)
    }

    @Test
    fun `the address field keeps what the user typed after a failure`() = runTest {
        client.failWith = IOException("nope")
        val viewModel = viewModel()
        viewModel.onIpInputChange("192.168.1.99")

        viewModel.testAndSave()

        // Retyping the whole address just to retry would be miserable.
        assertEquals("192.168.1.99", editingState(viewModel).ipInput)
        assertEquals(false, editingState(viewModel).testing)
    }

    @Test
    fun `reset pre-fills the current address without its scheme and clears errors`() = runTest {
        val viewModel = viewModel()
        viewModel.onIpInputChange("bad-address")
        client.failWith = IOException("nope")
        viewModel.testAndSave()

        viewModel.reset(currentIp = "http://192.168.1.50")

        assertEquals(SetupUiState.Editing(ipInput = "192.168.1.50"), viewModel.uiState.value)
    }

    @Test
    fun `reset with no saved address leaves the field empty`() = runTest {
        val viewModel = viewModel()

        viewModel.reset(currentIp = null)

        assertEquals(SetupUiState.Editing(ipInput = ""), viewModel.uiState.value)
    }
}
