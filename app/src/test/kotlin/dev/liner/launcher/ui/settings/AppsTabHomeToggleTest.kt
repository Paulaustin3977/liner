package dev.liner.launcher.ui.settings

import dev.liner.launcher.model.ColorSpec
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.ItemStyle
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.removeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The apps tab's home-screen checkbox.
 *
 * The decision is pulled out of the composable into [toggleHomeApp], so the
 * two rules the checkbox has to keep can be checked without a device: an
 * app that is unchecked must keep its customisation for the next check, and
 * an unchecked app must leave the home screen entirely — every copy of it.
 */
class AppsTabHomeToggleTest {

    private val style = ItemStyle(textSizeSp = 22f, color = ColorSpec.Solid(0xFF112233.toInt()))

    private val document = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "One",
                items = listOf(
                    HomeItem(id = "a", packageName = "p.other"),
                    HomeItem(id = "b", packageName = "p.mail", label = "Inbox", style = style),
                ),
            ),
            Section(
                id = "s2",
                title = "Two",
                items = listOf(
                    HomeItem(id = "c", packageName = "p.chat"),
                    HomeItem(id = "d", packageName = "p.mail", label = "Mail"),
                ),
            ),
        ),
    )

    /** The item with an id, wherever it is. */
    private fun LayoutDocument.item(id: String): HomeItem? =
        sections.flatMap { it.items }.firstOrNull { it.id == id }

    /** The section an item is in. */
    private fun LayoutDocument.sectionOf(itemId: String): String? =
        sections.firstOrNull { section -> section.items.any { it.id == itemId } }?.id

    @Test
    fun `unchecking an app takes every copy of it off the home screen`() {
        // The app is in two sections — an imported document can do that.
        val result = toggleHomeApp(document, "p.mail", null)

        assertEquals(
            listOf("a", "c"),
            result.document.sections.flatMap { it.items }.map { it.id },
        )
    }

    @Test
    fun `the removed item is remembered with its section and its place`() {
        val result = toggleHomeApp(document, "p.mail", null)

        val removed = result.removed
        assertNotNull(removed)
        // The first copy is the one that is kept.
        assertEquals("b", removed?.item?.id)
        assertEquals("s1", removed?.sectionId)
        assertEquals(1, removed?.index)
    }

    @Test
    fun `checking the app again keeps the label and the style`() {
        val off = toggleHomeApp(document, "p.mail", null)
        val on = toggleHomeApp(off.document, "p.mail", off.removed)

        val item = on.document.item("b")
        assertEquals("Inbox", item?.label)
        assertEquals(style, item?.style)
    }

    @Test
    fun `checking the app again puts it back in its own section`() {
        val off = toggleHomeApp(document, "p.mail", null)
        val on = toggleHomeApp(off.document, "p.mail", off.removed)

        // The item is not moved to the first section: it goes back where it
        // was.
        assertEquals("s1", on.document.sectionOf("b"))
        assertEquals(listOf("a", "b"), on.document.sections[0].items.map { it.id })
    }

    @Test
    fun `checking an app that was never on the home screen adds it to the first section`() {
        val result = toggleHomeApp(document, "p.new", null)

        val added = result.document.sections[0].items.filter { it.packageName == "p.new" }
        assertEquals(1, added.size)
        assertNull(result.removed)
    }

    @Test
    fun `checking an app again when its section is gone keeps the item and uses the first section`() {
        val off = toggleHomeApp(document, "p.chat", null)
        // The section the item was in is deleted while the app is off the
        // home screen.
        val withoutSection = off.document.removeSection("s2")
        val on = toggleHomeApp(withoutSection, "p.chat", off.removed)

        assertEquals("s1", on.document.sectionOf("c"))
        assertEquals("p.chat", on.document.item("c")?.packageName)
    }

    @Test
    fun `checking an app when the document has no section creates one`() {
        val empty = LayoutDocument()

        val result = toggleHomeApp(empty, "p.new", null)

        assertEquals(1, result.document.sections.size)
        assertEquals("p.new", result.document.sections[0].items.single().packageName)
    }
}
