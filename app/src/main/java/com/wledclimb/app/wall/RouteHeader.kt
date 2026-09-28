package com.wledclimb.app.wall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R

/**
 * The open route's name, and the three things that can be done to what is on
 * the wall.
 *
 * Split out of WallScreen for the same reason as the grid: a strip of the
 * screen with its own rules about when a name may be edited and when each
 * action applies, none of which are about navigation.
 */

@Composable
internal fun RouteTitle(
    routeId: Long?,
    routeName: String?,
    enabled: Boolean,
    onRename: (String) -> Unit
) {
    // Keyed on which route this is, not on what it is called. The name is the
    // thing being edited and two routes may share one, so keying on it meant
    // the field could carry over between routes and reset itself mid-rename.
    var editing by rememberSaveable(routeId) { mutableStateOf(false) }
    // Holds what is being typed, so it survives a rotation mid-rename. The
    // initial value here only covers being restored into an open field - the
    // value that matters is set when the field opens, because this block does
    // not re-run when the name changes under an unchanged id.
    var draft by rememberSaveable(routeId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(selectAll(routeName.orEmpty()))
    }
    val focusRequester = remember { FocusRequester() }
    // The field reports itself unfocused once on first composition, before the
    // request below has been granted. Committing on that would close the field
    // the instant it opened - which is exactly what it did.
    var hasFocused by remember(routeId) { mutableStateOf(false) }

    val commit = {
        val trimmed = draft.text.trim()
        if (trimmed.isNotBlank() && trimmed != routeName) onRename(trimmed)
        editing = false
    }

    if (editing) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            hasFocused = true
                        } else if (hasFocused && editing) {
                            commit()
                        }
                    }
            )

            // Somewhere to say "done" that is not the keyboard's own key and
            // not tapping away. Both of those work, and neither looks like a
            // way to finish - one is hidden behind whichever keyboard someone
            // uses, and the other is indistinguishable from giving up.
            IconButton(onClick = { commit() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.routes_rename_done)
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (routeName != null && enabled) {
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                onClickLabel = stringResource(R.string.routes_rename),
                                onClick = {
                                    // Seeded here, at the moment the field
                                    // opens, rather than by a key on the
                                    // remember below. A rename changes the
                                    // name without changing the id, so a key
                                    // on the id never re-runs and the field
                                    // reopens showing the name from before the
                                    // last rename. Reading it here cannot go
                                    // stale, because there is nothing between
                                    // this and the field appearing.
                                    draft = selectAll(routeName.orEmpty())
                                    editing = true
                                }
                            )
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = routeName ?: stringResource(R.string.routes_unsaved),
                // A step above the wall's name in the bar, which is titleLarge.
                // The route is the thing being worked on and stays the larger.
                style = MaterialTheme.typography.headlineSmall,
                color = if (routeName == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            // Only with a route to rename. Work nobody has saved has no name
            // to change - it gets one by being saved.
            if (routeName != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_rename),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp)
                )
            }
        }
    }
}

/**
 * Reset, save as and save, sitting on the wall rather than above the name.
 *
 * Against the top of the grid because that is what they act on, and pushed
 * right so they do not make a second column of icons under the ones in the
 * bar - two clusters in the same corner left it unclear which row owned which.
 *
 * All three stay put and grey out. A control that is sometimes absent is
 * harder to learn than one that is sometimes grey, since there is no way to
 * notice a button that is not there.
 */

@Composable
internal fun RouteActions(
    routeName: String?,
    modified: Boolean,
    enabled: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onReset, enabled = enabled && modified) {
            Icon(
                painter = painterResource(R.drawable.ic_reset),
                contentDescription = stringResource(R.string.routes_reset)
            )
        }

        // Copying a route to work from is worth offering before anything has
        // been changed, so this waits only for there being a route to copy.
        IconButton(onClick = onSaveAs, enabled = enabled && canSave && routeName != null) {
            Icon(
                painter = painterResource(R.drawable.ic_save_as),
                contentDescription = stringResource(R.string.routes_save_new)
            )
        }

        FilledTonalIconButton(onClick = onSave, enabled = enabled && canSave && modified) {
            Icon(
                painter = painterResource(R.drawable.ic_save),
                contentDescription = stringResource(R.string.routes_save_current),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
