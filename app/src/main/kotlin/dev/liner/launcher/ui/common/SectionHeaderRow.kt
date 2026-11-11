package dev.liner.launcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.model.ResolvedStyle
import dev.liner.launcher.platform.fontFamilyFor
import dev.liner.launcher.platform.weightFor

/** The title above a section of the home list, optionally followed by a hairline rule. */
@Composable
fun SectionHeaderRow(
    title: String,
    style: ResolvedStyle,
    uppercase: Boolean,
    showRule: Boolean,
    alignment: Alignment.Horizontal = Alignment.Start,
    modifier: Modifier = Modifier,
) {
    // A null colour is the "follow system" sentinel; see [LabelRow].
    val color = style.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onBackground

    // The title follows the same alignment as the rows of the section, so a
    // centred or right-aligned list is centred or right-aligned throughout.
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Text(
            text = if (uppercase) title.uppercase() else title,
            style = TextStyle(
                fontFamily = fontFamilyFor(style.fontKey),
                fontWeight = weightFor(style.weight),
                fontSize = style.sizeSp.sp,
                letterSpacing = style.letterSpacingEm.sp,
                color = color,
            ),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (showRule) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color.copy(alpha = 0.25f)),
            ) {}
        }
    }
}
