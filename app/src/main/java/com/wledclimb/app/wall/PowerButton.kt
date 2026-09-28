package com.wledclimb.app.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R

/**
 * The control that answers "is the wall on?" from across a garage - and, when
 * there is no controller to ask, says that instead.
 *
 * Three answers rather than two: on, off, and no wall to answer for. The third
 * is the same question with a different answer, which is why it lives here and
 * not in a banner. Each has a shape of its own as well as a colour, because
 * this is read at a glance by a six-year-old and colour alone is not an answer
 * for anyone who cannot see it:
 *
 * - **On**: a filled circle.
 * - **Off**: a solid ring.
 * - **Out of reach**: a dashed ring around a struck-through glyph. Tapping it
 *   asks the controller again, which is the only time the app does - a wall
 *   coming back is something whoever holds the device decides to act on.
 * - **Connecting**: a dashed ring around a spinner, and nothing to tap.
 */
@Composable
internal fun PowerButton(
    controller: ControllerState,
    /** False while something else is in flight; out of reach ignores it. */
    enabled: Boolean,
    onToggle: () -> Unit,
    onReconnect: () -> Unit
) {
    when (controller) {
        is ControllerState.Online -> {
            // The whole button lights up rather than just the glyph. A tinted
            // outline was too quiet to answer "is the wall on?" from across a
            // garage, which is the one question this control exists to answer
            // without being tapped.
            val on = controller.on
            val statusColour = if (on) WallStatusColors.on else WallStatusColors.off
            IconButton(onClick = onToggle, enabled = enabled) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        // Filled when on, hollow when off: the difference
                        // reads at a glance and does not rely on telling two
                        // colours apart.
                        .background(if (on) statusColour else Color.Transparent)
                        .border(width = 2.dp, color = statusColour, shape = CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_power),
                        // Says which way it will go, not which way it is.
                        contentDescription = stringResource(
                            if (on) R.string.wall_turn_off else R.string.wall_turn_on
                        ),
                        // On a filled circle the glyph has to contrast with
                        // the fill, not match it.
                        tint = if (on) MaterialTheme.colorScheme.surface else statusColour,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        is ControllerState.Offline -> {
            val colour = WallStatusColors.unreachable
            // Not gated on [enabled]: nothing can be in flight to a controller
            // that is not there, and this is the way back to it.
            IconButton(onClick = onReconnect) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .dashedRing(colour)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_power_off),
                        contentDescription = stringResource(R.string.wall_unreachable_retry),
                        tint = colour,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        ControllerState.Connecting -> {
            val colour = WallStatusColors.off
            val description = stringResource(R.string.wall_connecting)
            IconButton(onClick = {}, enabled = false) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .dashedRing(colour)
                        .semantics { contentDescription = description }
                ) {
                    CircularProgressIndicator(
                        color = colour,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * A ring drawn in dashes, inside the bounds like the solid border it stands
 * in for, so the three states line up exactly.
 */
private fun Modifier.dashedRing(colour: Color, width: Dp = 2.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawCircle(
        color = colour,
        radius = (size.minDimension - stroke) / 2,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        )
    )
}
