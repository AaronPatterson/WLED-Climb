package com.wledclimb.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
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

    // Lint is right that locking orientation is usually wrong, and it is
    // suppressed rather than obeyed: the check cannot see that this only
    // applies below the width where the layout has room, or that the
    // alternative on a phone is a wall too small to tap. Revisit it with a
    // landscape layout, not by unlocking.
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Portrait only where landscape has no room for the wall.
        //
        // A phone on its side has perhaps 360dp of height to hold an app bar,
        // the route's name, its actions, a square grid, the zoom controls and
        // the colour tray. The grid is what gets squeezed out, and the grid is
        // the app. A tablet has the height for all of it, and its landscape is
        // what the list-detail layout was chosen for, so it is left alone.
        //
        // Keyed on the smallest width rather than the current one, because
        // that does not change when the device turns - asking "is this a phone"
        // rather than "is it sideways right now".
        //
        // Blunter than it could be: the honest fix is a landscape layout that
        // puts the controls beside the wall instead of beneath it. Worth doing
        // if anyone ever wants to climb with their phone on its side.
        if (resources.configuration.smallestScreenWidthDp < LARGE_SCREEN_WIDTH_DP) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }

        // The platform draws edge-to-edge without being asked at the SDK this
        // app targets, so opt in explicitly and inset the content rather than
        // letting it slide under the status and navigation bars.
        //
        // Deliberately not naming the version it started at: the comment said
        // 35 while the app targeted 36, which is the way a number in a comment
        // usually ends up.
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
                                onSetupComplete = rootViewModel::onSetupComplete,
                                onUseDemoWall = rootViewModel::onUseDemoWall,
                                onCancel = rootViewModel::onSetupCancelled
                            )

                            is RootUiState.Ready -> WallRoute(
                                wledBaseUrl = state.wledBaseUrl,
                                onChangeController = rootViewModel::onChangeController
                            )

                            RootUiState.Demo -> WallRoute(
                                wledBaseUrl = null,
                                onChangeController = rootViewModel::onChangeController
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The width at which a device has the height, sideways, to show the wall and
 * its controls at once. Android's own boundary for a large screen, and the
 * same one the list-detail layout uses to decide it can show both panes.
 */
private const val LARGE_SCREEN_WIDTH_DP = 600
