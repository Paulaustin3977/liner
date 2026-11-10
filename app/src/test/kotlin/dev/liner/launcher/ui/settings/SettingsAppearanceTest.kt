package dev.liner.launcher.ui.settings

import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.platform.weightFor
import dev.liner.launcher.ui.common.formatSliderValue
import dev.liner.launcher.ui.common.isPaletteColorSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind the appearance tab and the settings screen.
 *
 * The tab and the screen are composables, so their decisions are pulled into
 * plain functions that can be checked without a device — the project's
 * unit-test source set has no Compose UI test dependency and the sandbox has
 * no emulator.
 */
class SettingsAppearanceTest {

    @Test
    fun `the tabs the rest of the app opens resolve to the right tab`() {
        // The drawer's gear opens "appearance" and the home screen's empty
        // state opens "apps"; both have to land on the tab they name.
        assertEquals(0, tabIndexFor("appearance"))
        assertEquals(1, tabIndexFor("apps"))
        assertEquals(2, tabIndexFor("sections"))
        assertEquals(3, tabIndexFor("importexport"))
        assertEquals(4, tabIndexFor("advanced"))
    }

    @Test
    fun `an unknown tab falls back to the first tab`() {
        assertEquals(0, tabIndexFor(null))
        assertEquals(0, tabIndexFor("nonsense"))
        assertEquals(0, tabIndexFor("Appearance"))
    }

    @Test
    fun `a background style is named the way the style chips spell it`() {
        assertEquals("solid", backgroundStyleLabel(BackgroundSpec.Solid(ColorSpec.FollowSystem)))
        assertEquals(
            "gradient",
            backgroundStyleLabel(
                BackgroundSpec.Gradient(ColorSpec.FollowSystem, ColorSpec.FollowSystem),
            ),
        )
        assertEquals("wallpaper", backgroundStyleLabel(BackgroundSpec.Wallpaper(0.35f)))
    }

    @Test
    fun `picking the solid style gives the theme a solid background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "solid")

        assertEquals(BackgroundSpec.Solid(ColorSpec.Solid(0xFF101014.toInt())), theme.background)
    }

    @Test
    fun `picking the gradient style gives the theme a gradient background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "gradient")

        assertEquals(
            BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF101014.toInt()),
                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
            ),
            theme.background,
        )
    }

    @Test
    fun `picking the wallpaper style gives the theme a wallpaper background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "wallpaper")

        assertEquals(BackgroundSpec.Wallpaper(0.35f), theme.background)
    }

    @Test
    fun `an unknown background style leaves the theme with a background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "nonsense")

        assertEquals(BackgroundSpec.Wallpaper(0.35f), theme.background)
    }

    @Test
    fun `picking a background style leaves the other tokens alone`() {
        val theme = ThemeTokens(listTextSizeSp = 33f, listFontFamily = "serif")

        val result = applyBackgroundStyle(theme, "solid")

        assertEquals(33f, result.listTextSizeSp, 0.001f)
        assertEquals("serif", result.listFontFamily)
    }

    @Test
    fun `a whole slider value is shown without decimals`() {
        assertEquals("20", formatSliderValue(20f))
        assertEquals("0", formatSliderValue(0f))
        assertEquals("-8", formatSliderValue(-8f))
    }

    @Test
    fun `a fractional slider value is shown with two decimals`() {
        // The decimal separator follows the locale, so the digits are what
        // the assertion pins.
        assertTrue(formatSliderValue(20.5f).matches(Regex("20[.,]50")))
        assertTrue(formatSliderValue(0.05f).matches(Regex("0[.,]05")))
    }

    @Test
    fun `the picked palette colour is the selected one`() {
        val argb = 0xFFE53935.toInt()

        assertTrue(isPaletteColorSelected(ColorSpec.Solid(argb), argb))
        assertFalse(isPaletteColorSelected(ColorSpec.Solid(argb), 0xFF43A047.toInt()))
    }

    @Test
    fun `following the system selects no palette colour`() {
        assertFalse(isPaletteColorSelected(ColorSpec.FollowSystem, 0xFF000000.toInt()))
    }

    @Test
    fun `a gradient change is applied to the background the theme holds now`() {
        // The transform derives the background from the CURRENT document, so
        // a change that landed in between is not undone by a stale copy.
        val theme = ThemeTokens(
            background = BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF101014.toInt()),
                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
                angleDegrees = 90,
            ),
        )

        val result = theme.withGradient { it.copy(angleDegrees = 180) }

        assertEquals(
            BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF101014.toInt()),
                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
                angleDegrees = 180,
            ),
            result.background,
        )
    }

    @Test
    fun `a theme without a gradient is left alone by a gradient change`() {
        val theme = ThemeTokens(background = BackgroundSpec.Solid(ColorSpec.FollowSystem))

        assertEquals(theme, theme.withGradient { it.copy(angleDegrees = 180) })
    }

    @Test
    fun `every weight the spec names is offered`() {
        assertEquals(
            listOf("100", "200", "300", "400", "500", "600", "700", "800", "900"),
            FONT_WEIGHTS,
        )
    }

    @Test
    fun `every offered weight renders differently`() {
        // A weight the platform cannot map would be a dead chip: it would
        // look the same as the default.
        val rendered = FONT_WEIGHTS.map { weightFor(it.toInt()) }

        assertEquals(
            "every weight the picker offers must look different",
            FONT_WEIGHTS.size,
            rendered.distinct().size,
        )
    }
}
