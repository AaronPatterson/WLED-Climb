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
fun WallRoute(wledBaseUrl: String?, onChangeController: () -> Unit) {
    // Null address means the practice wall: no controller, and nothing asks
    // the client anything - see DemoWall.
    //
    // Keyed on the address so switching controllers gets a WallViewModel
    // (and WledClient) pointed at the new one, not the previous instance.
    val context = LocalContext.current
    val wallViewModel: WallViewModel = viewModel(
        key = wledBaseUrl ?: DEMO_KEY,
        factory = LambdaViewModelFactory {
            val database = ClimbDatabase.instance(context)
            WallViewModel(
                // Built either way rather than made nullable: it is never
                // asked anything on the practice wall, and a nullable client
                // would put that question at every call site instead of here.
                client = HttpWledClient(baseUrl = wledBaseUrl ?: DEMO_KEY),
                walls = WallRepository(database.walls()),
                routes = RouteRepository(database.routes()),
                settings = DataStoreWledSettings(context.applicationContext),
                controllerAddress = wledBaseUrl ?: DEMO_KEY,
                demo = wledBaseUrl == null
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
        onPause = wallViewModel::pause,
        onRetry = wallViewModel::reconnect,
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

/**
 * Stands in for an address on the practice wall, as the ViewModel key and as
 * the base URL of a client nothing calls. `.invalid` is reserved by RFC 2606
 * precisely so it can never resolve, which is the point: if this ever were
 * requested, it fails rather than reaching something real.
 */
private const val DEMO_KEY = "http://practice.invalid"
