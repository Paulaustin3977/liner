package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A labelled slider.
 *
 * The label carries the current value, so the user reads the number as well
 * as the position of the handle.
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label: ${formatSliderValue(value)}")
        Slider(
            // A value outside the range would make the slider misbehave, so
            // the handle is clamped even though the stored token is not.
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/**
 * The value as the user reads it: whole numbers without a decimal part,
 * everything else with two decimals.
 */
internal fun formatSliderValue(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format("%.2f", value)
