package com.wledclimb.app.setup

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R

@Composable
fun SetupScreen(
    state: SetupUiState,
    onIpInputChange: (String) -> Unit,
    onTestAndSave: () -> Unit,
    onUseDemoWall: () -> Unit,
    onCancel: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Asked for here rather than at the root: this field sits in the
            // middle of an otherwise empty page, so the keyboard would cover
            // the thing being typed into.
            .imePadding()
            // Scrolls when it has to. Centred content is fine until it is
            // taller than what is left of the screen - a small phone with the
            // keyboard up and an error message showing - and without this the
            // practice wall block below the line is simply cut off, with
            // nothing to suggest it is there.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is SetupUiState.Editing -> {
                Text(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                OutlinedTextField(
                    value = state.ipInput,
                    onValueChange = onIpInputChange,
                    label = { Text(stringResource(R.string.setup_address_label)) },
                    enabled = !state.testing,
                    singleLine = true,
                    isError = state.problem != null,
                    // An address is not prose: no autocorrect or capitalisation,
                    // and the keyboard's action key submits instead of hunting
                    // for the button.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { onTestAndSave() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                if (state.problem != null) {
                    Text(
                        text = messageFor(state.problem),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                } else {
                    Button(onClick = onTestAndSave, modifier = Modifier.padding(top = 16.dp)) {
                        Text(text = stringResource(R.string.setup_test_and_save))
                    }

                    // Under the action that this screen exists to perform, not
                    // above it. Setting an address is what someone came here
                    // to do; leaving is the way out rather than the offer.
                    //
                    // Null on a first run, where leaving would show a wall the
                    // app has no address for.
                    onCancel?.let { cancel ->
                        TextButton(onClick = cancel, modifier = Modifier.padding(top = 8.dp)) {
                            Text(text = stringResource(R.string.setup_cancel))
                        }
                    }
                }

                // Below a line, with its own heading, rather than as a third
                // button under the other two. Stacked with them it read as one
                // more way out of this screen; what it actually is is the
                // answer to a different question - what to do when there is no
                // controller to type an address for. Someone with a wall in
                // front of them should be able to ignore this whole block, and
                // someone without one should find it without having to fail at
                // connecting first.
                HorizontalDivider(modifier = Modifier.padding(top = 32.dp))

                Text(
                    text = stringResource(R.string.setup_no_wall_heading),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp)
                )
                Text(
                    text = stringResource(R.string.setup_no_wall_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // Outlined rather than filled: an offer, not the thing this
                // screen is for. Still a button rather than a text link,
                // because it does something as real as the one above.
                OutlinedButton(
                    onClick = onUseDemoWall,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(text = stringResource(R.string.setup_try_practice_wall))
                }
            }

            is SetupUiState.Connected -> {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.setup_connected),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun messageFor(problem: SetupProblem): String = when (problem) {
    SetupProblem.EmptyAddress -> stringResource(R.string.setup_error_empty_address)
    is SetupProblem.Unreachable -> stringResource(R.string.setup_error_unreachable, problem.address)
    is SetupProblem.NotAWledMatrix -> stringResource(R.string.setup_error_not_wled, problem.address)
}
