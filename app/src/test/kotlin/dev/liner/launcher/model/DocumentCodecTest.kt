package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `every background and colour variant survives a round trip`() {
        val backgrounds = listOf(
            BackgroundSpec.Solid(ColorSpec.Solid(0xFF102030.toInt())),
            BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF000000.toInt()),
                to = ColorSpec.FollowSystem,
                angleDegrees = 45,
            ),
            BackgroundSpec.Wallpaper(dim = 0.5f),
        )

        backgrounds.forEach { background ->
            val original = LayoutDocument(theme = ThemeTokens(background = background))

            val decoded = DocumentCodec.decode(DocumentCodec.encode(original))

            assertNotNull(decoded)
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `a hand written payload decodes to the expected document`() {
        val json = """
            {
              "version": 1,
              "sections": [
                {
                  "id": "s1",
                  "title": "Daily",
                  "items": [ { "id": "a", "packageName": "com.mail" } ]
                }
              ],
              "theme": {
                "background": { "type": "wallpaper", "dim": 0.5 },
                "textColor": { "type": "system" }
              }
            }
        """.trimIndent()

        val decoded = DocumentCodec.decode(json)

        assertNotNull(decoded)
        assertEquals(BackgroundSpec.Wallpaper(0.5f), decoded!!.theme.background)
        assertEquals(ColorSpec.FollowSystem, decoded.theme.textColor)
        assertEquals("Daily", decoded.sections[0].title)
        assertEquals("com.mail", decoded.sections[0].items[0].packageName)
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

    @Test
    fun `a document saved before the first-run prompt was added still decodes`() {
        // The field was added to the document after the first release, so
        // every layout saved before that must still load — with the prompt
        // not yet dismissed.
        val json = """{"version":1,"sections":[],"theme":{}}"""

        val decoded = DocumentCodec.decode(json)

        assertNotNull(decoded)
        assertFalse(decoded!!.firstRunPromptDismissed)
    }

    @Test
    fun `the first-run prompt's dismissal survives a round trip`() {
        val original = LayoutDocument(firstRunPromptDismissed = true)

        val decoded = DocumentCodec.decode(DocumentCodec.encode(original))

        assertNotNull(decoded)
        assertTrue(decoded!!.firstRunPromptDismissed)
    }
}
