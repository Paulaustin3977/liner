package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleResolverTest {

    private val theme = ThemeTokens(
        textColor = ColorSpec.Solid(0xFF112233.toInt()),
        listFontFamily = "serif",
        listTextSizeSp = 20f,
        listFontWeight = "500",
        letterSpacingEm = 0.1f,
        sectionTitleSizeSp = 13f,
        sectionTitleColor = ColorSpec.Solid(0xFF445566.toInt()),
    )

    @Test
    fun `global theme resolves when there is no override`() {
        val resolved = StyleResolver.resolveItemStyle(theme, null)

        assertEquals("serif", resolved.fontKey)
        assertEquals(20f, resolved.sizeSp, 0.001f)
        assertEquals(500, resolved.weight)
        assertEquals(0.1f, resolved.letterSpacingEm, 0.001f)
        assertEquals(0xFF112233.toInt(), resolved.colorArgb)
    }

    @Test
    fun `per item override wins over the global theme`() {
        val override = ItemStyle(
            textSizeSp = 33f,
            color = ColorSpec.Solid(0xFFAABBCC.toInt()),
            fontWeight = "900",
            letterSpacingEm = 0.25f,
        )

        val resolved = StyleResolver.resolveItemStyle(theme, override)

        assertEquals(33f, resolved.sizeSp, 0.001f)
        assertEquals(0xFFAABBCC.toInt(), resolved.colorArgb)
        assertEquals(900, resolved.weight)
        assertEquals(0.25f, resolved.letterSpacingEm, 0.001f)
    }

    @Test
    fun `a partial override only replaces the fields it sets`() {
        val resolved = StyleResolver.resolveItemStyle(theme, ItemStyle(textSizeSp = 12f))

        assertEquals(12f, resolved.sizeSp, 0.001f)
        assertEquals(500, resolved.weight)
        assertEquals(0xFF112233.toInt(), resolved.colorArgb)
    }

    @Test
    fun `follow system colour resolves to null`() {
        val resolved = StyleResolver.resolveItemStyle(ThemeTokens(), null)

        assertNull(resolved.colorArgb)
    }

    @Test
    fun `an unparseable weight falls back to 400`() {
        val resolved = StyleResolver.resolveItemStyle(ThemeTokens(listFontWeight = "banana"), null)

        assertEquals(400, resolved.weight)
    }

    @Test
    fun `section header style comes from the theme`() {
        val resolved = StyleResolver.resolveSectionHeaderStyle(theme)

        assertEquals(13f, resolved.sizeSp, 0.001f)
        assertEquals(600, resolved.weight)
        assertEquals(0xFF445566.toInt(), resolved.colorArgb)
    }
}
