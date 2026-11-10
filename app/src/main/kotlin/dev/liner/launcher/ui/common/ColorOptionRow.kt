package dev.liner.launcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.ColorSpec

/** The colours the user can pick from. */
private val PALETTE = listOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt(), 0xFFCCCCCC.toInt(),
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFF6D4C41.toInt(),
)

/**
 * A labelled group of colour choices: the palette's swatches plus a chip
 * that follows the system's own colour.
 *
 * The selected swatch is drawn with a thicker border, so the choice is
 * visible without relying on colour alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorOptionRow(
    label: String,
    selected: ColorSpec,
    onSelect: (ColorSpec) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label)
        PALETTE.forEach { argb ->
            Box(
                Modifier
                    .size(32.dp)
                    .background(Color(argb))
                    .border(
                        width = if (isPaletteColorSelected(selected, argb)) 3.dp else 1.dp,
                        color = Color.White,
                    )
                    .clickable { onSelect(ColorSpec.Solid(argb)) },
            )
        }
        FilterChip(
            selected = selected is ColorSpec.FollowSystem,
            onClick = { onSelect(ColorSpec.FollowSystem) },
            label = { Text("Follow system") },
        )
    }
}

/** Whether [argb] is the colour the user picked. */
internal fun isPaletteColorSelected(selected: ColorSpec, argb: Int): Boolean =
    selected is ColorSpec.Solid && selected.argb == argb
