package com.wledclimb.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wledclimb.app.settings.DataStoreWledSettings
import com.wledclimb.app.setup.SetupRoute
import com.wledclimb.app.theme.WledClimbTheme
import com.wledclimb.app.wall.WallRoute

class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels {
        LambdaViewModelFactory { RootViewModel(DataStoreWledSettings(applicationContext)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 draws edge-to-edge whether we ask or not, so opt in
        // explicitly and inset the content rather than letting it slide under
        // the status and navigation bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WledClimbTheme {
                // The Surface fills the display so its background reaches the
                // screen edges; only the content inside is inset.
                Surface(modifier = Modifier.fillMaxSize()) {
                    // System bars and the cutout, deliberately not the
                    // keyboard. safeDrawing includes the IME, so every screen
                    // shrank to make room for it - on the wall that meant the
                    // grid collapsing while a name was typed, which is the one
                    // thing worth still being able to see.
                    //
                    // Screens whose field would end up underneath the keyboard
                    // ask for that room themselves, which is only the setup
                    // screen: its field is in the middle of an otherwise empty
                    // page, where the wall's is at the top.
                    Box(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        )
                    ) {
                        val rootState by rootViewModel.uiState.collectAsState()
                        when (val state = rootState) {
                            is RootUiState.Loading -> LoadingScreen()

                            is RootUiState.NeedsSetup -> SetupRoute(
                                currentUrl = state.currentUrl,
                                onSetupComplete = rootViewModel::onSetupComplete
                            )

                            is RootUiState.Ready -> WallRoute(
                                wledBaseUrl = state.wledBaseUrl,
                                onChangeController = rootViewModel::onChangeController
                            )
                        }
                    }
                }
            }
        }
    }
}
