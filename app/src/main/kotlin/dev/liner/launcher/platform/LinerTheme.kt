package dev.liner.launcher.platform

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The font families a user can pick from in Settings → Appearance.
 *
 * Only genuinely distinct families are offered. The platform's
 * "sans-serif-light", "sans-serif-medium" and "sans-serif-condensed" faces
 * cannot be referenced from Compose without bundling the font files as
 * resources — `android.R.font` does not expose them and Compose's `Font`
 * factory only accepts a resource id — so offering them would present three
 * choices that render identically to plain sans-serif.
 */
val FONT_KEYS = listOf(
    "sans-serif",
    "serif",
    "monospace",
    "cursive",
)

fun fontFamilyFor(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

fun weightFor(value: Int): FontWeight = when (value) {
    100 -> FontWeight.Thin
    200 -> FontWeight.ExtraLight
    300 -> FontWeight.Light
    400 -> FontWeight.Normal
    500 -> FontWeight.Medium
    600 -> FontWeight.SemiBold
    700 -> FontWeight.Bold
    800 -> FontWeight.ExtraBold
    900 -> FontWeight.Black
    else -> FontWeight.Normal
}

@Composable
fun LinerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFE8E8ED),
            onPrimary = Color(0xFF101014),
            background = Color(0xFF101014),
            surface = Color(0xFF101014),
            onBackground = Color(0xFFF5F5F7),
            onSurface = Color(0xFFF5F5F7),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1A1A1E),
            onPrimary = Color(0xFFFAFAFC),
            background = Color(0xFFFAFAFC),
            surface = Color(0xFFFAFAFC),
            onBackground = Color(0xFF1A1A1E),
            onSurface = Color(0xFF1A1A1E),
        )
    }

    MaterialTheme(colorScheme = colors, content = content)
}
