package dev.liner.launcher.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import of a layout document.
 *
 * The validator is the gate every import goes through: it decides whether a
 * payload can be read at all, whether it is too new for this build, and —
 * when it can be read — what it would change about the current layout.
 */
class ImportExportTest {

    private val current = LayoutDocument(
        sections = listOf(Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a")))),
    )

    @Test
    fun `a valid document is accepted`() {
        val result = ImportValidator.validate(DocumentCodec.encode(current))

        assertNotNull(result.document)
        assertNull(result.error)
    }

    @Test
    fun `malformed json is rejected with an explanation`() {
        val result = ImportValidator.validate("{not json")

        assertNull(result.document)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("readable"))
    }

    @Test
    fun `a newer document version is rejected with an explanation`() {
        val result = ImportValidator.validate("""{"version":99,"sections":[],"theme":{}}""")

        assertNull(result.document)
        assertTrue(result.error!!.contains("newer"))
    }

    @Test
    fun `the summary lists what changed`() {
        val incoming = LayoutDocument(
            sections = listOf(
                Section(id = "x", title = "New", items = listOf(HomeItem(id = "z", packageName = "p.z"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming))

        assertTrue(result.summary.isNotEmpty())
        assertTrue(result.summary.any { it.contains("p.z") })
    }

    @Test
    fun `an older document version is accepted`() {
        val result = ImportValidator.validate("""{"version":0,"sections":[],"theme":{}}""")

        assertNotNull(result.document)
        assertNull(result.error)
    }

    @Test
    fun `the summary names the apps that are removed`() {
        val result = ImportValidator.validate(DocumentCodec.encode(LayoutDocument()), current)

        assertTrue(result.summary.any { it.contains("Removes") })
        assertTrue(result.summary.any { it.contains("p.a") })
    }

    @Test
    fun `the summary reports a changed number of sections`() {
        val incoming = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s2", title = "Yours"),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming), current)

        assertTrue(result.summary.any { it.contains("1 becomes 2") })
    }

    @Test
    fun `the summary reports a replaced theme`() {
        val themed = current.copy(theme = ThemeTokens().copy(listTextSizeSp = 30f))

        val result = ImportValidator.validate(DocumentCodec.encode(themed), current)

        assertTrue(result.summary.any { it.contains("theme") })
    }

    @Test
    fun `the summary says so when nothing changed`() {
        val result = ImportValidator.validate(DocumentCodec.encode(current), current)

        assertEquals(listOf("No visible changes"), result.summary)
    }

    @Test
    fun `an exported document is the document itself, with no wrapper around it`() {
        val element = Json.parseToJsonElement(DocumentCodec.encode(current)).jsonObject

        assertTrue("version" in element)
        assertTrue("sections" in element)
        assertTrue("theme" in element)
    }

    @Test
    fun `the summary reports an app that moved to another section`() {
        // The apps are the same, so the package sets do not differ — but the
        // home screen is rearranged, and the user has to be told.
        val incoming = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Mine"),
                Section(id = "s2", title = "Work", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming), current)

        assertTrue(result.summary.any { it.contains("Work") && it.contains("p.a") })
        assertTrue(result.summary.any { it.contains("Mine") && it.contains("p.a") })
    }

    @Test
    fun `the summary reports a renamed section`() {
        val incoming = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Yours", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming), current)

        assertTrue(result.summary.any { it.contains("Mine") && it.contains("Yours") })
    }

    @Test
    fun `the summary reports a section whose apps are in another order`() {
        val two = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Mine",
                    items = listOf(
                        HomeItem(id = "a", packageName = "p.a"),
                        HomeItem(id = "b", packageName = "p.b"),
                    ),
                ),
            ),
        )
        val reordered = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Mine",
                    items = listOf(
                        HomeItem(id = "b", packageName = "p.b"),
                        HomeItem(id = "a", packageName = "p.a"),
                    ),
                ),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(reordered), two)

        assertTrue(result.summary.any { it.contains("Reorders") })
    }

    @Test
    fun `the summary reports a section that is only in the imported layout`() {
        val incoming = current.copy(sections = current.sections + Section(id = "s2", title = "Work"))

        val result = ImportValidator.validate(DocumentCodec.encode(incoming), current)

        assertTrue(result.summary.any { it.contains("Adds") && it.contains("Work") })
    }

    @Test
    fun `the summary reports a section that is only in the current layout`() {
        val result = ImportValidator.validate(DocumentCodec.encode(LayoutDocument()), current)

        assertTrue(result.summary.any { it.contains("Removes") && it.contains("Mine") })
    }

    @Test
    fun `the summary reports sections that are in another order`() {
        // The sections are the same ones — the same titles, the same apps
        // in them — but they are in another order, so the import moves the
        // sections around on the home screen.
        val two = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s2", title = "Work", items = listOf(HomeItem(id = "b", packageName = "p.b"))),
            ),
        )
        val swapped = LayoutDocument(
            sections = listOf(
                Section(id = "s2", title = "Work", items = listOf(HomeItem(id = "b", packageName = "p.b"))),
                Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(swapped), two)

        assertTrue(result.summary.any { it.contains("Reorders") && it.contains("sections") })
        assertTrue(result.summary.none { it.contains("No visible changes") })
    }

    @Test
    fun `the summary reports a renamed app`() {
        val incoming = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a", label = "Chat"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming), current)

        assertTrue(result.summary.any { it.contains("Renames") && it.contains("p.a") && it.contains("Chat") })
        assertTrue(result.summary.none { it.contains("No visible changes") })
    }

    @Test
    fun `the summary reports an app whose custom name is taken away`() {
        val named = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a", label = "Chat"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(current), named)

        assertTrue(result.summary.any { it.contains("Renames") && it.contains("p.a") })
        assertTrue(result.summary.none { it.contains("No visible changes") })
    }

    @Test
    fun `the summary reports a restyled app`() {
        val incoming = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Mine",
                    items = listOf(HomeItem(id = "a", packageName = "p.a", style = ItemStyle(textSizeSp = 30f))),
                ),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming), current)

        assertTrue(result.summary.any { it.contains("Restyles") && it.contains("p.a") })
        assertTrue(result.summary.none { it.contains("No visible changes") })
    }

    @Test
    fun `an identical layout reports no visible changes`() {
        // The same layout — same sections in the same order, same names and
        // same styles — changes nothing, so nothing may be reported.
        val same = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Mine",
                    items = listOf(
                        HomeItem(id = "a", packageName = "p.a", label = "Chat", style = ItemStyle(textSizeSp = 30f)),
                    ),
                ),
                Section(id = "s2", title = "Work", items = listOf(HomeItem(id = "b", packageName = "p.b"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(same), same)

        assertEquals(listOf("No visible changes"), result.summary)
    }
}
