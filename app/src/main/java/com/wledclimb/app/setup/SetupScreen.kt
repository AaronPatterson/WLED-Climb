package com.wledclimb.app.setup

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R

@Composable
fun SetupScreen(
    state: SetupUiState,
    onIpInputChange: (String) -> Unit,
    onTestAndSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Asked for here rather than at the root: this field sits in the
            // middle of an otherwise empty page, so the keyboard would cover
            // the thing being typed into.
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is SetupUiState.Editing -> {
                Text(text = stringResource(R.string.setup_title))
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
