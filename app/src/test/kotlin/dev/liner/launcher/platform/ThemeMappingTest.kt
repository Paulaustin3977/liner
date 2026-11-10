package dev.liner.launcher.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeMappingTest {

    @Test
    fun `known font keys map to the expected families`() {
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif"))
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif-light"))
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif-medium"))
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif-condensed"))
        assertEquals(FontFamily.Serif, fontFamilyFor("serif"))
        assertEquals(FontFamily.Monospace, fontFamilyFor("monospace"))
        assertEquals(FontFamily.Cursive, fontFamilyFor("cursive"))
    }

    @Test
    fun `an unknown font key falls back to sans serif`() {
        assertEquals(FontFamily.SansSerif, fontFamilyFor("comic"))
    }

    @Test
    fun `numeric weights map to font weights`() {
        assertEquals(FontWeight.Light, weightFor(300))
        assertEquals(FontWeight.Normal, weightFor(400))
        assertEquals(FontWeight.Bold, weightFor(700))
        assertEquals(FontWeight.Black, weightFor(900))
    }

    @Test
    fun `an out of range weight falls back to normal`() {
        assertEquals(FontWeight.Normal, weightFor(42))
    }
}
