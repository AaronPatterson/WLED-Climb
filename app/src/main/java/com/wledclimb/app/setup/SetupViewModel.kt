package com.wledclimb.app.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledclimb.app.network.WledConfigException
import com.wledclimb.app.network.HttpWledClient
import com.wledclimb.app.network.WledClient
import com.wledclimb.app.settings.WledSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SetupViewModel"

/**
 * The setup screen: enter a WLED controller address, check it really is a
 * WLED wall, and save it for future launches.
 *
 * A single instance is reused for the app's whole lifetime (see SetupRoute),
 * so [reset] is called each time the screen is (re-)entered rather than
 * relying on a fresh constructor call to establish the starting state.
 *
 * [clientFactory] exists because the address isn't known until the user types
 * it, so the client can't simply be constructed once and injected.
 */
class SetupViewModel(
    private val settings: WledSettings,
    private val clientFactory: (baseUrl: String) -> WledClient = { HttpWledClient(baseUrl = it) }
) : ViewModel() {

    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Editing(ipInput = ""))
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /**
     * Clears out any leftover state from a previous visit (a stale error, an
     * old address being edited) and pre-fills [currentIp] if there is one -
     * i.e. this screen is being reopened to change an already-working address
     * rather than being set up for the first time.
     */
    fun reset(currentIp: String?) {
        _uiState.value = SetupUiState.Editing(
            ipInput = currentIp.orEmpty().removePrefix("http://").removePrefix("https://")
        )
    }

    fun onIpInputChange(ip: String) {
        _uiState.value = SetupUiState.Editing(ipInput = ip)
    }

    fun testAndSave() {
        val current = _uiState.value as? SetupUiState.Editing ?: return
        if (current.testing) return

        val ip = current.ipInput.trim()
        if (ip.isEmpty()) {
            _uiState.value = current.copy(problem = SetupProblem.EmptyAddress)
            return
        }

        _uiState.value = current.copy(testing = true, problem = null)
        viewModelScope.launch {
            val baseUrl = if (ip.startsWith("http://") || ip.startsWith("https://")) ip else "http://$ip"
            _uiState.value = try {
                // Parsed, not just fetched: anything that answers with HTTP 200 would
                // otherwise pass setup, and the user would only find out on the next
                // screen - by which point they can no longer correct the address.
                // Parsed, not just fetched: anything answering with HTTP 200
                // would otherwise pass setup, and the user would find out on
                // the next screen - by which point the address is no longer in
                // front of them to correct.
                clientFactory(baseUrl).getWall()
                settings.saveWledIp(baseUrl)
                SetupUiState.Connected(ip = baseUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WledConfigException) {
                Log.e(TAG, "testAndSave() reached $baseUrl, but its config is unusable", e)
                SetupUiState.Editing(ipInput = ip, problem = SetupProblem.NotAWledMatrix(ip))
            } catch (e: Exception) {
                Log.e(TAG, "testAndSave() failed to reach WLED", e)
                SetupUiState.Editing(ipInput = ip, problem = SetupProblem.Unreachable(ip))
            }
        }
    }
}
