package com.wledclimb.app.gaps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R
import com.wledclimb.app.grid.GapCell
import com.wledclimb.app.grid.GapLayout
import com.wledclimb.app.wall.GridControls
import com.wledclimb.app.wall.MIN_GRID_SCALE
import com.wledclimb.app.wall.WallStatusColors
import com.wledclimb.app.wall.ZoomableGrid
import com.wledclimb.app.wall.clampGridPan
import com.wledclimb.app.wall.clampGridScale
import com.wledclimb.app.wall.gridPanDelta

/** The one question the editor can be asking, named by what it asks. */
private enum class GapEditorDialog { Save, Reset, Leave }

/**
 * Editing which grid positions have holds, laid out like building a route:
 * the wall in the middle, reset and save above it, zoom below it, and a tray
 * of what can be painted at the bottom where the colours usually are.
 *
 * A pushed screen rather than a pane beside the routes, so the wall's power
 * and brightness are not here - see docs/navigation.md. This is the adult
 * setting the wall up, not someone climbing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GapEditorScreen(
    state: GapEditorUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPaint: (x: Int, y: Int) -> Unit,
    onBrushSelect: (GapCell) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onCancelPin: () -> Unit
) {
    var dialog by rememberSaveable { mutableStateOf<GapEditorDialog?>(null) }
    val editing = state as? GapEditorUiState.Editing
    // A restart cannot be walked away from: the wall has to be read again
    // once the controller is back, and that is what finishing does.
    val canLeave = state !is GapEditorUiState.Restarting && state !is GapEditorUiState.Saved

    // Edits ask before being thrown away, the way an unsaved route does.
    val leave: () -> Unit = {
        if (editing?.modified == true) {
            dialog = GapEditorDialog.Leave
        } else if (canLeave) {
            onBack()
        }
    }
    // Back has to come here rather than leave the app: this screen replaces
    // the wall rather than sitting above it in a back stack.
    BackHandler(onBack = leave)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.gaps_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = leave, enabled = canLeave) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.gaps_back)
                    )
                }
            }
        )

        when (state) {
            GapEditorUiState.Loading -> Waiting(stringResource(R.string.gaps_loading))

            is GapEditorUiState.LoadFailed -> CenteredColumn {
                Text(
                    text = stringResource(R.string.gaps_load_failed_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(state.problem.messageRes),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Text(text = stringResource(R.string.wall_retry))
                }
            }

            is GapEditorUiState.Editing -> EditingContent(
                state = state,
                onPaint = onPaint,
                onBrushSelect = onBrushSelect,
                onReset = { dialog = GapEditorDialog.Reset },
                onSave = { dialog = GapEditorDialog.Save }
            )

            GapEditorUiState.Restarting, GapEditorUiState.Saved ->
                Waiting(stringResource(R.string.gaps_restarting))
        }
    }

    // Not one of the dialogs above: the view model asks for it, when the
    // controller refuses the file, rather than a button here.
    val pinRequest = editing?.pin
    if (editing != null && pinRequest != null) {
        PinDialog(
            rejected = pinRequest == PinRequest.Rejected,
            checking = editing.saving,
            onDismiss = onCancelPin,
            onSubmit = onSubmitPin
        )
    }

    // Only while editing: every question here is about the edits.
    if (editing != null) {
        when (dialog) {
            GapEditorDialog.Save -> ConfirmDialog(
                title = stringResource(R.string.gaps_save_title),
                body = stringResource(R.string.gaps_save_body),
                confirm = stringResource(R.string.gaps_save_confirm),
                onDismiss = { dialog = null },
                onConfirm = {
                    dialog = null
                    onSave()
                }
            )

            GapEditorDialog.Reset -> ConfirmDialog(
                title = stringResource(R.string.gaps_reset_title),
                body = stringResource(R.string.gaps_reset_body),
                confirm = stringResource(R.string.gaps_reset),
                onDismiss = { dialog = null },
                onConfirm = {
                    dialog = null
                    onReset()
                }
            )

            GapEditorDialog.Leave -> ConfirmDialog(
                title = stringResource(R.string.gaps_discard_title),
                body = stringResource(R.string.gaps_discard_body),
                confirm = stringResource(R.string.routes_discard),
                onDismiss = { dialog = null },
                onConfirm = {
                    dialog = null
                    onBack()
                }
            )

            null -> Unit
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) }
        }
    )
}

/**
 * The controller's settings PIN, which WLED wants before it will take a file.
 *
 * Four digits, because that is all WLED compares - so the button waits for
 * four rather than sending something that cannot be right. Hidden as it is
 * typed, the way WLED's own PIN page hides it.
 */
@Composable
private fun PinDialog(
    rejected: Boolean,
    checking: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val canSubmit = pin.length == PIN_LENGTH && !checking
    // Emptied as it goes, so a PIN that is refused does not have to be
    // deleted before the right one is typed.
    val submit: () -> Unit = {
        onSubmit(pin)
        pin = ""
    }

    LaunchedEffect(rejected) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(stringResource(R.string.gaps_pin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.gaps_pin_body))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { typed ->
                        pin = typed.filter { it.isDigit() }.take(PIN_LENGTH)
                    },
                    enabled = !checking,
                    singleLine = true,
                    label = { Text(stringResource(R.string.gaps_pin_label)) },
                    isError = rejected,
                    supportingText = if (rejected) {
                        { Text(stringResource(R.string.gaps_pin_rejected)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit) submit() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = submit) {
                if (checking) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(stringResource(R.string.gaps_pin_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !checking, onClick = onDismiss) {
                Text(stringResource(R.string.routes_cancel))
            }
        }
    )
}

/** WLED's settings PIN is four digits, and it compares no more than that. */
private const val PIN_LENGTH = 4

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun Waiting(message: String) {
    CenteredColumn {
        CircularProgressIndicator()
        Text(
            text = message,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun EditingContent(
    state: GapEditorUiState.Editing,
    onPaint: (x: Int, y: Int) -> Unit,
    onBrushSelect: (GapCell) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(MIN_GRID_SCALE) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val enabled = !state.saving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        // Where a route's actions sit, and the same two of them that apply
        // here. There is no "save as": a controller has one gap file.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.gaps_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onReset, enabled = enabled && state.modified) {
                Icon(
                    painter = painterResource(R.drawable.ic_reset),
                    contentDescription = stringResource(R.string.gaps_reset)
                )
            }
            FilledTonalIconButton(onClick = onSave, enabled = enabled && state.modified) {
                if (state.saving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_save),
                        contentDescription = stringResource(R.string.gaps_save),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        state.problem?.let { problem ->
            Text(
                text = stringResource(problem.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GapGrid(
                layout = state.layout,
                onPaint = onPaint,
                scale = scale,
                pan = pan,
                onTransform = { gesturePan, gestureZoom, gridSize ->
                    viewport = gridSize
                    scale = clampGridScale(scale * gestureZoom)
                    pan = clampGridPan(pan + gridPanDelta(gesturePan, scale), scale, gridSize)
                },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(top = 8.dp)
            )
            GridControls(
                scale = scale,
                canClear = false,
                onClearWall = null,
                onZoom = { factor ->
                    scale = clampGridScale(scale * factor)
                    pan = clampGridPan(pan, scale, viewport)
                },
                modifier = Modifier.padding(top = 8.dp)
            )
            BrushTray(
                selected = state.brush,
                enabled = enabled,
                onSelect = onBrushSelect,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/**
 * The gap file drawn on the wall's grid. Every position a panel covers is a
 * control, gaps included - they are the point of the screen - and positions
 * no panel covers are drawn as nothing, as they are on the wall.
 */
@Composable
private fun GapGrid(
    layout: GapLayout,
    onPaint: (x: Int, y: Int) -> Unit,
    scale: Float,
    pan: Offset,
    onTransform: (pan: Offset, zoom: Float, viewport: Size) -> Unit,
    modifier: Modifier = Modifier
) {
    val holdCount = layout.toWall().holdCount
    val description = pluralStringResource(
        R.plurals.wall_grid_description,
        holdCount,
        holdCount,
        layout.width,
        layout.height
    )
    val holdColor = WallStatusColors.gridCell
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    ZoomableGrid(
        columns = layout.width,
        rows = layout.height,
        description = description,
        onCellTap = onPaint,
        scale = scale,
        pan = pan,
        onTransform = onTransform,
        modifier = modifier
    ) { x, y, cellSize ->
        GapGridCell(
            cell = layout.cellAt(x, y),
            column = x,
            row = y,
            size = cellSize,
            holdColor = holdColor,
            outlineColor = outlineColor,
            onTap = { onPaint(x, y) }
        )
    }
}

/**
 * One position. Taps are handled by the grid, as on the wall; the onClick
 * below is for screen readers.
 */
@Composable
private fun GapGridCell(
    cell: GapCell?,
    column: Int,
    row: Int,
    size: Dp,
    holdColor: Color,
    outlineColor: Color,
    onTap: () -> Unit
) {
    if (cell == null) {
        // Not covered by a panel, so not a control and nothing to announce.
        Box(modifier = Modifier.size(size).clearAndSetSemantics { })
        return
    }
    val description = stringResource(
        R.string.gaps_cell_description,
        column + 1,
        row + 1,
        stringResource(cell.labelRes)
    )
    GapCellLook(
        cell = cell,
        holdColor = holdColor,
        outlineColor = outlineColor,
        modifier = Modifier
            .size(size)
            .padding(1.dp)
            .semantics {
                contentDescription = description
                onClick {
                    onTap()
                    true
                }
            }
    )
}

/**
 * How each kind of position looks, shared by the grid and the tray so the
 * tray is a legend for the grid rather than a second vocabulary.
 *
 * A hold is solid, like an unlit hold on the wall. An unused LED is the same
 * colour in outline, with a dot for the LED that is there. No LED is a faint
 * outline: a place, and nothing in it.
 */
@Composable
private fun GapCellLook(
    cell: GapCell,
    holdColor: Color,
    outlineColor: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .then(
                when (cell) {
                    GapCell.Hold -> Modifier.background(holdColor)
                    GapCell.Unused -> Modifier.border(2.dp, holdColor, shape)
                    GapCell.NoLed -> Modifier.border(1.dp, outlineColor, shape)
                }
            )
    ) {
        if (cell == GapCell.Unused) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.3f)
                    .clip(CircleShape)
                    .background(holdColor)
            )
        }
    }
}

/**
 * What a tap paints, where the colour tray is when building a route. Labelled,
 * unlike the colours: a colour names itself, and "unused LED" does not.
 */
@Composable
private fun BrushTray(
    selected: GapCell,
    enabled: Boolean,
    onSelect: (GapCell) -> Unit,
    modifier: Modifier = Modifier
) {
    val holdColor = WallStatusColors.gridCell
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.selectableGroup()
    ) {
        for (brush in GapCell.entries) {
            val isSelected = brush == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(brush) }
                    )
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .border(
                            width = if (isSelected) 4.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    GapCellLook(
                        cell = brush,
                        holdColor = holdColor,
                        outlineColor = outlineColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = stringResource(brush.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private val GapCell.labelRes: Int
    get() = when (this) {
        GapCell.Hold -> R.string.gaps_hold
        GapCell.Unused -> R.string.gaps_unused
        GapCell.NoLed -> R.string.gaps_no_led
    }

private val GapEditorProblem.messageRes: Int
    get() = when (this) {
        GapEditorProblem.Unreachable -> R.string.wall_error_unreachable
        GapEditorProblem.NotAWledMatrix -> R.string.wall_error_not_wled
        GapEditorProblem.Locked -> R.string.gaps_error_locked
        GapEditorProblem.NotRestarted -> R.string.gaps_error_not_restarted
    }
