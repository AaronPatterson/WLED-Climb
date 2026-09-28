package com.wledclimb.app.wall

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wledclimb.app.R

/**
 * The control that answers "is the wall on?" from across a garage - and, when
 * this device is not talking to the controller, says why instead.
 *
 * Each answer has a shape of its own as well as a colour, because this is read
 * at a glance by a six-year-old and colour alone is not an answer for anyone
 * who cannot see it:
 *
 * - **On**: a filled circle. **Off**: a solid ring. Tapping switches.
 * - **Out of reach**: a dashed ring round a struck-through glyph. Tapping asks
 *   the controller again, which is the only time the app does.
 * - **Paused**: a dashed ring round a pause glyph. Tapping goes back online.
 * - **Connecting**: a dashed ring round a spinner, and nothing to tap.
 *
 * A long press pauses: working offline with the controller in reach, so a
 * route can be built while someone climbs the one on the wall. Hidden on
 * purpose - it is for the adult building routes, not for the child climbing -
 * and not the only way in: the menu has it too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PowerButton(
    controller: ControllerState,
    /** False while a power change is in flight. Only switching waits on it. */
    enabled: Boolean,
    onToggle: () -> Unit,
    onReconnect: () -> Unit,
    onPause: () -> Unit
) {
    val pauseLabel = stringResource(R.string.wall_work_offline)
    // Not while the controller is already out of reach. Pausing there changes
    // nothing anyone could observe - both states push nothing, and neither
    // reconnects unattended - so the gesture would be a no-op that feels like
    // a fault. Offline is waiting for the controller; there is no preference
    // to express until it answers.
    val canPause = controller is ControllerState.Online
    val onClick: (() -> Unit)? = when (controller) {
        is ControllerState.Online -> onToggle.takeIf { enabled }
        is ControllerState.Offline, ControllerState.Paused -> onReconnect
        ControllerState.Connecting -> null
        // The practice wall has no controller to switch, and the bar leaves
        // this button out entirely there - see WallTopBar. Spelled out rather
        // than folded into an else so that a fifth state cannot arrive here
        // and quietly become inert.
        ControllerState.Demo -> null
    }

    // Built from a Box rather than an IconButton, which has no long press.
    // Sized and clipped to match one, so the ripple and the touch target are
    // what the other buttons in the bar have.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = onClick != null || canPause,
                role = Role.Button,
                onClick = { onClick?.invoke() },
                // Vibrates when it takes effect, which combinedClickable does
                // itself: without it there is no telling a long press that
                // worked from a tap held too long.
                onLongClickLabel = pauseLabel.takeIf { canPause },
                onLongClick = onPause.takeIf { canPause }
            )
    ) {
        when (controller) {
            // Not drawn: WallTopBar omits the button on a practice wall.
            ControllerState.Demo -> Unit

            is ControllerState.Online -> {
                // The whole button lights up rather than just the glyph. A
                // tinted outline was too quiet to answer "is the wall on?"
                // from across a garage, which is the one question this
                // control exists to answer without being tapped.
                val on = controller.on
                val statusColour = if (on) WallStatusColors.on else WallStatusColors.off
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

            is ControllerState.Offline -> DashedGlyph(
                colour = WallStatusColors.unreachable,
                glyph = R.drawable.ic_power_off,
                description = stringResource(R.string.wall_unreachable_retry)
            )

            // Neutral, like off: paused is something chosen, not a fault.
            ControllerState.Paused -> DashedGlyph(
                colour = WallStatusColors.off,
                glyph = R.drawable.ic_pause,
                description = stringResource(R.string.wall_paused_resume)
            )

            ControllerState.Connecting -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .dashedRing(WallStatusColors.off)
            ) {
                CircularProgressIndicator(
                    color = WallStatusColors.off,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DashedGlyph(colour: Color, glyph: Int, description: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .dashedRing(colour)
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = description,
            tint = colour,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * A ring drawn in dashes, inside the bounds like the solid border it stands
 * in for, so the states line up exactly.
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
