package dev.liner.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.platform.FONT_KEYS
import dev.liner.launcher.ui.common.ChoiceRow
import dev.liner.launcher.ui.common.ColorOptionRow
import dev.liner.launcher.ui.common.SliderRow
import kotlin.math.roundToInt

/**
 * Every font weight the spec's 100-900 range names.
 *
 * The platform's `weightFor` maps all nine, and `StyleResolver` parses any
 * numeric weight string, so every one of them is a real choice.
 */
internal val FONT_WEIGHTS = listOf(
    "100", "200", "300", "400", "500", "600", "700", "800", "900",
)

/**
 * The appearance tab: every theme token the user can change.
 *
 * The tab writes the tokens straight into the document through
 * [onThemeChange]; it never reads a resolved style back, because a settings
 * screen is where the tokens are *set*, not interpreted.
 */
@Composable
fun AppearanceTab(
    theme: ThemeTokens,
    onThemeChange: ((ThemeTokens) -> ThemeTokens) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Background", modifier = Modifier.padding(top = 8.dp))
        ChoiceRow(
            label = "Style",
            options = listOf("solid", "gradient", "wallpaper"),
            selected = backgroundStyleLabel(theme.background),
            onSelect = { style ->
                onThemeChange { current -> applyBackgroundStyle(current, style) }
            },
        )

        when (val background = theme.background) {
            is BackgroundSpec.Solid -> ColorOptionRow(
                label = "Colour",
                selected = background.color,
                onSelect = { spec -> onThemeChange { it.copy(background = BackgroundSpec.Solid(spec)) } },
            )

            is BackgroundSpec.Gradient -> {
                ColorOptionRow(
                    label = "From",
                    selected = background.from,
                    // The transform derives the background from the CURRENT
                    // document, not from the value captured when the tab was
                    // composed: a stale copy would silently undo whatever
                    // else changed the background in the meantime.
                    onSelect = { spec -> onThemeChange { current -> current.withGradient { it.copy(from = spec) } } },
                )
                ColorOptionRow(
                    label = "To",
                    selected = background.to,
                    onSelect = { spec -> onThemeChange { current -> current.withGradient { it.copy(to = spec) } } },
                )
                SliderRow(
                    label = "Angle",
                    value = background.angleDegrees.toFloat(),
                    range = 0f..360f,
                    steps = 359,
                    onValueChange = { angle -> onThemeChange { current -> current.withAngle(angle) } },
                )
            }

            is BackgroundSpec.Wallpaper -> SliderRow(
                label = "Dim",
                value = background.dim,
                range = 0f..0.9f,
                // Five percent steps: the scrim is a percentage, and a
                // tenth of a percent is not a thing a user can see.
                steps = 8,
                onValueChange = { dim -> onThemeChange { it.copy(background = BackgroundSpec.Wallpaper(dim)) } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ColorOptionRow(
            label = "Text colour",
            selected = theme.textColor,
            onSelect = { spec -> onThemeChange { it.copy(textColor = spec) } },
        )

        ChoiceRow(
            label = "Font",
            options = FONT_KEYS,
            selected = theme.listFontFamily,
            onSelect = { key -> onThemeChange { it.copy(listFontFamily = key) } },
        )

        SliderRow(
            label = "Text size",
            value = theme.listTextSizeSp,
            range = 12f..48f,
            // The token is a whole number of points, so the slider snaps to
            // it — otherwise the stored value would be 23.4567 while the
            // label read "23.46".
            steps = 35,
            onValueChange = { size -> onThemeChange { it.copy(listTextSizeSp = size) } },
        )

        ChoiceRow(
            label = "Weight",
            // Every weight the spec's 100-900 range names. The platform's
            // font weights are all reachable, and `StyleResolver` parses any
            // numeric weight string.
            options = FONT_WEIGHTS,
            selected = theme.listFontWeight,
            onSelect = { weight -> onThemeChange { it.copy(listFontWeight = weight) } },
        )

        SliderRow(
            label = "Letter spacing",
            value = theme.letterSpacingEm,
            range = -0.05f..0.2f,
            // Two decimal places, which is the precision the token is read
            // back at.
            steps = 24,
            onValueChange = { spacing -> onThemeChange { it.copy(letterSpacingEm = spacing) } },
        )

        SliderRow(
            label = "Row spacing",
            value = theme.itemSpacingDp,
            range = 0f..32f,
            steps = 31,
            onValueChange = { spacing -> onThemeChange { it.copy(itemSpacingDp = spacing) } },
        )

        SliderRow(
            label = "Side padding",
            value = theme.screenPaddingHdp,
            range = 0f..48f,
            steps = 47,
            onValueChange = { padding -> onThemeChange { it.copy(screenPaddingHdp = padding) } },
        )

        SliderRow(
            label = "Top and bottom padding",
            value = theme.screenPaddingVdp,
            range = 0f..48f,
            steps = 47,
            onValueChange = { padding -> onThemeChange { it.copy(screenPaddingVdp = padding) } },
        )

        ChoiceRow(
            label = "Alignment",
            options = listOf("start", "center", "end"),
            selected = theme.alignment,
            onSelect = { alignment -> onThemeChange { it.copy(alignment = alignment) } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ChoiceRow(
            label = "Clock",
            options = listOf("hidden", "large", "small"),
            selected = theme.clockStyle,
            onSelect = { style -> onThemeChange { it.copy(clockStyle = style) } },
        )

        ChoiceRow(
            label = "Clock format",
            options = listOf("system", "12h", "24h"),
            selected = theme.clockFormat,
            onSelect = { format -> onThemeChange { it.copy(clockFormat = format) } },
        )

        ChoiceRow(
            label = "Date",
            options = listOf("none", "short", "medium", "long"),
            selected = theme.dateStyle,
            onSelect = { style -> onThemeChange { it.copy(dateStyle = style) } },
        )

        SliderRow(
            label = "Section title size",
            value = theme.sectionTitleSizeSp,
            range = 8f..24f,
            steps = 15,
            onValueChange = { size -> onThemeChange { it.copy(sectionTitleSizeSp = size) } },
        )

        ColorOptionRow(
            label = "Section title colour",
            selected = theme.sectionTitleColor,
            onSelect = { spec -> onThemeChange { it.copy(sectionTitleColor = spec) } },
        )

        ChoiceRow(
            label = "Section title case",
            options = listOf("upper", "as typed"),
            selected = if (theme.sectionTitleUppercase) "upper" else "as typed",
            onSelect = { case -> onThemeChange { it.copy(sectionTitleUppercase = case == "upper") } },
        )

        ChoiceRow(
            label = "Section title rule",
            options = listOf("on", "off"),
            selected = if (theme.sectionTitleRule) "on" else "off",
            onSelect = { value -> onThemeChange { it.copy(sectionTitleRule = value == "on") } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ChoiceRow(
            label = "Icons",
            options = listOf("off", "on"),
            selected = if (theme.showIcons) "on" else "off",
            onSelect = { value -> onThemeChange { it.copy(showIcons = value == "on") } },
        )

        SliderRow(
            label = "Icon size",
            value = theme.iconSizeDp.toFloat(),
            range = 16f..64f,
            steps = 47,
            onValueChange = { size -> onThemeChange { it.withIconSize(size) } },
        )

        ChoiceRow(
            label = "Drawer style",
            options = listOf("icons", "text"),
            selected = theme.drawerStyle,
            onSelect = { style -> onThemeChange { it.copy(drawerStyle = style) } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        TextButton(
            onClick = { onThemeChange { ThemeTokens() } },
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Text("Reset appearance")
        }
    }
}

/** The name of the background style, as the style chips spell it. */
internal fun backgroundStyleLabel(background: BackgroundSpec): String = when (background) {
    is BackgroundSpec.Solid -> "solid"
    is BackgroundSpec.Gradient -> "gradient"
    is BackgroundSpec.Wallpaper -> "wallpaper"
}

/**
 * Applies [transform] to the theme's gradient, if it has one. A theme
 * whose background is not a gradient is left alone — the gradient controls
 * are only reachable when the background IS a gradient.
 */
internal fun ThemeTokens.withGradient(
    transform: (BackgroundSpec.Gradient) -> BackgroundSpec.Gradient,
): ThemeTokens {
    val current = background
    if (current !is BackgroundSpec.Gradient) return this
    return copy(background = transform(current))
}

/**
 * The theme with the gradient angle the slider's value stands for.
 *
 * The value is rounded, not truncated: the slider's tick value is a float
 * that can land a hair below the tick it stands for — the 113° tick
 * evaluates to 112.99999237f — and truncation would store one degree less
 * than the tick the user aimed at, which makes a few of the ticks
 * unreachable.
 */
internal fun ThemeTokens.withAngle(angle: Float): ThemeTokens =
    withGradient { it.copy(angleDegrees = angle.roundToInt()) }

/**
 * The theme with the icon size the slider's value stands for, rounded the
 * same way as the gradient angle.
 */
internal fun ThemeTokens.withIconSize(size: Float): ThemeTokens =
    copy(iconSizeDp = size.roundToInt())

/**
 * The theme after the user picks a background style.
 *
 * Each style comes with its own starting colours, so picking one shows a
 * background the user can then adjust. An unknown style falls back to the
 * wallpaper rather than leaving the theme without a background.
 */
internal fun applyBackgroundStyle(theme: ThemeTokens, style: String): ThemeTokens = when (style) {
    "solid" -> theme.copy(background = BackgroundSpec.Solid(ColorSpec.Solid(0xFF101014.toInt())))
    "gradient" -> theme.copy(
        background = BackgroundSpec.Gradient(
            from = ColorSpec.Solid(0xFF101014.toInt()),
            to = ColorSpec.Solid(0xFF2A2A32.toInt()),
        ),
    )
    else -> theme.copy(background = BackgroundSpec.Wallpaper(0.35f))
}
