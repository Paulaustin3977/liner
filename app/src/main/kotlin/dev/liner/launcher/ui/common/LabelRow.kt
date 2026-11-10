package dev.liner.launcher.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.model.ResolvedStyle
import dev.liner.launcher.platform.fontFamilyFor
import dev.liner.launcher.platform.weightFor

/**
 * One row of the home list: an optional icon and the app's label.
 *
 * A disabled row is a label whose app is no longer installed: it is dimmed
 * rather than hidden, so the user still sees what their layout used to hold.
 */
@Composable
fun LabelRow(
    text: String,
    style: ResolvedStyle,
    alignment: Alignment.Horizontal,
    icon: ImageBitmap?,
    iconSizeDp: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // A null colour is the "follow system" sentinel. The model cannot resolve
    // it to a concrete colour, so the current configuration supplies it.
    val color = style.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onBackground

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, alignment),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSizeDp.dp),
            )
        }
        Text(
            text = text,
            style = TextStyle(
                fontFamily = fontFamilyFor(style.fontKey),
                fontWeight = weightFor(style.weight),
                fontSize = style.sizeSp.sp,
                letterSpacing = style.letterSpacingEm.sp,
                color = if (enabled) color else color.copy(alpha = 0.4f),
            ),
        )
    }
}
