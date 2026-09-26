package com.wledclimb.app.setup

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wledclimb.app.LambdaViewModelFactory
import com.wledclimb.app.settings.DataStoreWledSettings
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Owns SetupViewModel and wires it to SetupScreen - the one place allowed to
 * depend on SetupViewModel directly (see the Composables section of
 * docs/kotlin-style.md).
 */
@Composable
fun SetupRoute(
    currentUrl: String?,
    onSetupComplete: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val setupViewModel: SetupViewModel = viewModel(
        factory = LambdaViewModelFactory { SetupViewModel(DataStoreWledSettings(context)) }
    )
    // Runs once each time this screen is (re-)entered - e.g. after tapping
    // "Change controller". Resets first (clearing whatever was left over
    // from last visit) before watching for a fresh success, so a leftover
    // Connected from last time can't be seen as a new one.
    LaunchedEffect(Unit) {
        setupViewModel.reset(currentIp = currentUrl)
        setupViewModel.uiState
            .filterIsInstance<SetupUiState.Connected>()
            .collect { connected -> onSetupComplete(connected.ip) }
    }
    // Back leaves setup rather than the app, for the same reason the button
    // exists. Only when there is a working address behind it - on a first run
    // back should still close the app, because there is nothing else to show.
    val canCancel = currentUrl != null
    BackHandler(enabled = canCancel, onBack = onCancel)

    val setupState by setupViewModel.uiState.collectAsState()
    SetupScreen(
        state = setupState,
        onIpInputChange = setupViewModel::onIpInputChange,
        onTestAndSave = setupViewModel::testAndSave,
        onCancel = onCancel.takeIf { canCancel }
    )
}
