package com.wledclimb.app.gaps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wledclimb.app.LambdaViewModelFactory
import com.wledclimb.app.network.HttpWledClient

/**
 * Owns GapEditorViewModel and wires it to GapEditorScreen - see the
 * Composables section of docs/kotlin-style.md.
 *
 * [session] tells one visit to the editor from the next. The view model is
 * keyed on it, so each visit reads the gap file afresh rather than reopening
 * on the last visit's edits, or on its "saved". A visit's view model outlives
 * the visit, being held by the activity until it finishes, which costs one
 * small idle object per visit - the editor is opened when holds are moved,
 * not every day.
 *
 * [onClose] says whether a layout was saved, because the wall behind this has
 * to be read again if one was.
 */
@Composable
fun GapEditorRoute(
    wledBaseUrl: String,
    session: Int,
    onClose: (saved: Boolean) -> Unit
) {
    val viewModel: GapEditorViewModel = viewModel(
        key = "gaps:$session:$wledBaseUrl",
        factory = LambdaViewModelFactory {
            GapEditorViewModel(client = HttpWledClient(baseUrl = wledBaseUrl))
        }
    )
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state) {
        if (state == GapEditorUiState.Saved) onClose(true)
    }

    GapEditorScreen(
        state = state,
        onBack = { onClose(false) },
        onRetry = viewModel::load,
        onPaint = viewModel::paint,
        onBrushSelect = viewModel::selectBrush,
        onReset = viewModel::reset,
        onSave = viewModel::save,
        onSubmitPin = viewModel::saveWithPin,
        onCancelPin = viewModel::cancelPin
    )
}
