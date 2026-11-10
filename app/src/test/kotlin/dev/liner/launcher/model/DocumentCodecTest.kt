package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCodecTest {

    @Test
    fun `round trips a fully populated document`() {
        val original = LayoutDocument(
            version = 1,
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Daily",
                    items = listOf(
                        HomeItem(
                            id = "i1",
                            packageName = "com.example.mail",
                            label = "Mail",
                            style = ItemStyle(
                                textSizeSp = 28f,
                                color = ColorSpec.Solid(0xFF00FF00.toInt()),
                                fontWeight = "700",
                                letterSpacingEm = 0.05f,
                            ),
                        ),
                        HomeItem(id = "i2", packageName = "com.example.maps"),
                    ),
                ),
                Section(id = "s2", title = null),
            ),
            theme = ThemeTokens(
                background = BackgroundSpec.Gradient(
                    from = ColorSpec.Solid(0xFF102030.toInt()),
                    to = ColorSpec.FollowSystem,
                    angleDegrees = 45,
                ),
                textColor = ColorSpec.Solid(0xFFFFFFFF.toInt()),
                showIcons = true,
                iconSizeDp = 40,
                alignment = "center",
            ),
            hiddenFromDrawer = listOf("com.example.hidden"),
            autoPruneUninstalled = true,
        )

        val decoded = DocumentCodec.decode(DocumentCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val json = """{"version":1,"sections":[],"theme":{},"somethingNew":{"a":1}}"""

        val decoded = DocumentCodec.decode(json)

        assertNotNull(decoded)
        assertEquals(1, decoded!!.version)
    }

    @Test
    fun `rejects a document from a newer version`() {
        val json = """{"version":99,"sections":[],"theme":{}}"""

        assertNull(DocumentCodec.decode(json))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(DocumentCodec.decode("{not json"))
    }

    @Test
    fun `defaults are applied when fields are absent`() {
        val decoded = DocumentCodec.decode("""{"version":1}""")

        assertNotNull(decoded)
        assertTrue(decoded!!.sections.isEmpty())
        assertEquals(20f, decoded.theme.listTextSizeSp, 0.001f)
        assertEquals(false, decoded.theme.showIcons)
        assertEquals(BackgroundSpec.Wallpaper(0.35f), decoded.theme.background)
        assertEquals(ColorSpec.FollowSystem, decoded.theme.textColor)
    }
}
