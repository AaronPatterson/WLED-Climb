package com.wledclimb.app.wall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wledclimb.app.R
import com.wledclimb.app.network.MAX_BRIGHTNESS
import com.wledclimb.app.network.MIN_USABLE_BRIGHTNESS

/**
 * Master brightness, shown under the top bar while it is open.
 *
 * Drawn over the content rather than above it in the layout. Inline, opening
 * and closing this pushed the grid down and let it spring back - and since the
 * row closes on a tap, aiming at a hold moved the grid up under the finger
 * before the tap resolved, so the hold below the intended one was painted.
 * Floating costs a shadow and buys a wall that stays where it is put.
 */
// Slider's track and thumb slots are still marked experimental. They are used
// here because the defaults are not fit for this control - see the comments on
// each - and the risk is a compile error on a future upgrade, not a runtime
// surprise.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrightnessControl(
    brightness: Int,
    enabled: Boolean,
    onBrightnessChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val usableRange = MAX_BRIGHTNESS - MIN_USABLE_BRIGHTNESS
    // Seeded when this appears and owned by the slider from then on. Syncing it
    // back from the wall was a race: for a short movement onValueChangeFinished
    // can land in the same frame as onValueChange, before the new value has
    // propagated, so the thumb snapped back to where it started.
    var position by remember { mutableFloatStateOf(brightness.toFloat()) }
    // Reported across the usable range rather than against 255, so the ends read
    // 0% and 100%. The wall never actually reaches zero, but a slider whose
    // bottom says 3% looks broken, and "as dim as this goes" is what it means.
    val percent = ((position - MIN_USABLE_BRIGHTNESS) / usableRange * 100).toInt()
    // Resolved out here: semantics runs outside composition and cannot reach a
    // string resource from inside the lambda.
    val description =
        pluralStringResource(R.plurals.wall_brightness_description, percent, percent)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // Wider than usual: the slider's ends sit near the screen edges,
            // which belong to the system's back gesture.
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        ) {
            Slider(
                value = position,
                onValueChange = {
                    position = it
                    onBrightnessChange(it.toInt())
                },
                // Never reaches zero. Brightness rising from zero is what makes
                // WLED unfreeze its segments and drop the route, and it would
                // give the wall a second way to be off.
                valueRange = MIN_USABLE_BRIGHTNESS.toFloat()..MAX_BRIGHTNESS.toFloat(),
                enabled = enabled,
                track = { sliderState ->
                    // Thicker than the default 4dp. A taller track is a taller
                    // touch area, which matters on a control that gets grabbed
                    // rather than aimed at.
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(14.dp)
                    )
                },
                thumb = {
                    // A plain circle, because Material3's own thumb stretches
                    // into a pill while being dragged and settles back on
                    // release. Deliberate on their part, and wrong on a control
                    // sitting quietly under a top bar.
                    //
                    // Not, as an earlier version of this comment claimed,
                    // because the default swallows the press. It does not: the
                    // drag failure was a touch target too small, and the size
                    // here is what fixed it.
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    // Taller than the default, so a press landing a little above
                    // or below the track still counts.
                    .height(48.dp)
                    // Android drives its back gesture from both screen edges and
                    // wins over whatever is drawn there.
                    .systemGestureExclusion()
                    .semantics { contentDescription = description }
            )
            Text(
                text = stringResource(R.string.wall_brightness_percent, percent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                // Fixed, or the slider's own width changes with the number and
                // slides the thumb out from under the finger.
                modifier = Modifier.width(44.dp)
            )
        }
    }
}
