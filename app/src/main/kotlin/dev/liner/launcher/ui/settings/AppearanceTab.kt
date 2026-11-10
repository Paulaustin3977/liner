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
                    onSelect = { spec -> onThemeChange { it.copy(background = background.copy(from = spec)) } },
                )
                ColorOptionRow(
                    label = "To",
                    selected = background.to,
                    onSelect = { spec -> onThemeChange { it.copy(background = background.copy(to = spec)) } },
                )
                SliderRow(
                    label = "Angle",
                    value = background.angleDegrees.toFloat(),
                    range = 0f..360f,
                    onValueChange = { angle -> onThemeChange { it.copy(background = background.copy(angleDegrees = angle.toInt())) } },
                )
            }

            is BackgroundSpec.Wallpaper -> SliderRow(
                label = "Dim",
                value = background.dim,
                range = 0f..0.9f,
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
            onValueChange = { size -> onThemeChange { it.copy(listTextSizeSp = size) } },
        )

        ChoiceRow(
            label = "Weight",
            options = listOf("300", "400", "500", "700", "900"),
            selected = theme.listFontWeight,
            onSelect = { weight -> onThemeChange { it.copy(listFontWeight = weight) } },
        )

        SliderRow(
            label = "Letter spacing",
            value = theme.letterSpacingEm,
            range = -0.05f..0.2f,
            onValueChange = { spacing -> onThemeChange { it.copy(letterSpacingEm = spacing) } },
        )

        SliderRow(
            label = "Row spacing",
            value = theme.itemSpacingDp,
            range = 0f..32f,
            onValueChange = { spacing -> onThemeChange { it.copy(itemSpacingDp = spacing) } },
        )

        SliderRow(
            label = "Side padding",
            value = theme.screenPaddingHdp,
            range = 0f..48f,
            onValueChange = { padding -> onThemeChange { it.copy(screenPaddingHdp = padding) } },
        )

        SliderRow(
            label = "Top and bottom padding",
            value = theme.screenPaddingVdp,
            range = 0f..48f,
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
            onValueChange = { size -> onThemeChange { it.copy(iconSizeDp = size.toInt()) } },
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
