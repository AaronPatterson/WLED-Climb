package com.wledclimb.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledclimb.app.settings.WledSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen the app should show. */
sealed interface RootUiState {
    data object Loading : RootUiState

    /** [currentUrl] pre-fills the setup screen when returning to it to change an already-working address. */
    data class NeedsSetup(val currentUrl: String? = null) : RootUiState

    data class Ready(val wledBaseUrl: String) : RootUiState
}

/**
 * Decides whether to show the setup screen or wall control, based on whether
 * a WLED controller address has already been saved.
 */
class RootViewModel(private val settings: WledSettings) : ViewModel() {

    private val _uiState = MutableStateFlow<RootUiState>(RootUiState.Loading)
    val uiState: StateFlow<RootUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUrl = settings.wledIp.first()
            _uiState.value = if (savedUrl != null) RootUiState.Ready(savedUrl) else RootUiState.NeedsSetup()
        }
    }

    fun onSetupComplete(wledBaseUrl: String) {
        _uiState.value = RootUiState.Ready(wledBaseUrl)
    }

    /** Called from the wall screen to go back and point the app at a different controller. */
    fun onChangeController() {
        val current = _uiState.value as? RootUiState.Ready ?: return
        _uiState.value = RootUiState.NeedsSetup(currentUrl = current.wledBaseUrl)
    }

    /**
     * Leaves setup without changing anything, returning to the wall that was
     * already working.
     *
     * Setup used to be a one-way door: it is reachable from a menu on the wall
     * screen, and the only button on it saves. A six-year-old who opens it
     * finds an address in a text box and no way back - and can edit the
     * address before working that out. The app is not broken at that point,
     * but there is no way for them to discover that.
     *
     * Does nothing on a first run, where there is no working address behind
     * the screen and leaving it would show a wall the app cannot reach.
     */
    fun onSetupCancelled() {
        val current = _uiState.value as? RootUiState.NeedsSetup ?: return
        val previous = current.currentUrl ?: return
        _uiState.value = RootUiState.Ready(previous)
    }
}
