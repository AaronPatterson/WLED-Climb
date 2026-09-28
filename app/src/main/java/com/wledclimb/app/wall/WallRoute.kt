package com.wledclimb.app.wall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wledclimb.app.LambdaViewModelFactory
import com.wledclimb.app.network.HttpWledClient
import com.wledclimb.app.settings.DataStoreWledSettings
import com.wledclimb.app.storage.ClimbDatabase
import com.wledclimb.app.storage.RouteRepository
import com.wledclimb.app.storage.WallRepository

/**
 * Owns WallViewModel and wires it to WallScreen - the one place allowed to
 * depend on WallViewModel directly (see the Composables section of
 * docs/kotlin-style.md).
 */
@Composable
fun WallRoute(wledBaseUrl: String, onChangeController: () -> Unit) {
    // Keyed on the address so switching controllers gets a WallViewModel
    // (and WledClient) pointed at the new one, not the previous instance.
    val context = LocalContext.current
    val wallViewModel: WallViewModel = viewModel(
        key = wledBaseUrl,
        factory = LambdaViewModelFactory {
            val database = ClimbDatabase.instance(context)
            WallViewModel(
                client = HttpWledClient(baseUrl = wledBaseUrl),
                walls = WallRepository(database.walls()),
                routes = RouteRepository(database.routes()),
                settings = DataStoreWledSettings(context.applicationContext),
                controllerAddress = wledBaseUrl
            )
        }
    )
    val wallState by wallViewModel.uiState.collectAsState()
    val routes by wallViewModel.savedRoutes.collectAsState()

    // Turning the picked file into bytes happens here rather than in the view
    // model: a Uri is only meaningful next to a ContentResolver, and the view
    // model is the part worth being able to test without one.
    val resolver = context.contentResolver
    val backupResult by wallViewModel.backupResult.collectAsState()
    backupResult?.let { outcome ->
        RouteBackupOutcomeDialog(
            outcome = outcome,
            onDismiss = wallViewModel::clearBackupResult
        )
    }

    WallScreen(
        state = wallState,
        routes = routes,
        onToggle = wallViewModel::toggleWall,
        onHoldTap = wallViewModel::toggleHold,
        onBrightnessChange = wallViewModel::setBrightness,
        onColorSelect = wallViewModel::selectColor,
        onClearWall = wallViewModel::clearWall,
        onRetry = wallViewModel::refresh,
        onChangeController = onChangeController,
        onNewRoute = wallViewModel::newRoute,
        onRevertRoute = wallViewModel::revertRoute,
        onLoadRoute = wallViewModel::loadRoute,
        onSaveRoute = wallViewModel::saveRoute,
        onRenameRoute = wallViewModel::renameRoute,
        onDeleteRoute = wallViewModel::deleteRoute,
        onExportRoutes = { uri -> wallViewModel.exportRoutes { resolver.openOutputStream(uri) } },
        onImportRoutes = { uri -> wallViewModel.importRoutes { resolver.openInputStream(uri) } }
    )
}
