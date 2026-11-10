package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditsTest {

    private fun doc() = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "One",
                items = listOf(
                    HomeItem(id = "a", packageName = "p.a"),
                    HomeItem(id = "b", packageName = "p.b"),
                    HomeItem(id = "c", packageName = "p.c"),
                ),
            ),
            Section(
                id = "s2",
                title = "Two",
                items = listOf(HomeItem(id = "d", packageName = "p.d")),
            ),
        ),
    )

    @Test
    fun `moveItem reorders within a section`() {
        val result = doc().moveItem("c", toSectionId = "s1", toIndex = 0)

        assertEquals(listOf("c", "a", "b"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `moveItem moves across sections`() {
        val result = doc().moveItem("a", toSectionId = "s2", toIndex = 0)

        assertEquals(listOf("b", "c"), result.sections[0].items.map { it.id })
        assertEquals(listOf("a", "d"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `moveItem clamps an out of range index`() {
        val result = doc().moveItem("a", toSectionId = "s2", toIndex = 99)

        assertEquals(listOf("d", "a"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `moveItem to an unknown section is a no-op`() {
        val before = doc()
        assertEquals(before, before.moveItem("a", toSectionId = "nope", toIndex = 0))
    }

    @Test
    fun `moveItem of an unknown item is a no-op`() {
        val before = doc()
        assertEquals(before, before.moveItem("zzz", toSectionId = "s1", toIndex = 0))
    }

    @Test
    fun `removeItem drops it from its section`() {
        val result = doc().removeItem("b")

        assertEquals(listOf("a", "c"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `renameItem sets a custom label`() {
        assertEquals("Mail", doc().renameItem("a", "Mail").sections[0].items[0].label)
    }

    @Test
    fun `renameItem with null reverts to the system label`() {
        val renamed = doc().renameItem("a", "Mail")
        assertNull(renamed.renameItem("a", null).sections[0].items[0].label)
    }

    @Test
    fun `renameItem with a blank label reverts to the system label`() {
        assertNull(doc().renameItem("a", "   ").sections[0].items[0].label)
    }

    @Test
    fun `setItemStyle attaches and clears an override`() {
        val style = ItemStyle(textSizeSp = 30f)

        val withStyle = doc().setItemStyle("a", style)
        assertEquals(style, withStyle.sections[0].items[0].style)

        assertNull(withStyle.setItemStyle("a", null).sections[0].items[0].style)
    }

    @Test
    fun `addItem appends to the target section`() {
        val result = doc().addItem(HomeItem(id = "e", packageName = "p.e"), sectionId = "s2")

        assertEquals(listOf("d", "e"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `addItem ignores an unknown section`() {
        val before = doc()
        assertEquals(before, before.addItem(HomeItem(id = "e", packageName = "p.e"), sectionId = "nope"))
    }

    @Test
    fun `addItem ignores a duplicate id`() {
        val before = doc()
        assertEquals(before, before.addItem(HomeItem(id = "a", packageName = "p.a"), sectionId = "s2"))
    }

    @Test
    fun `reorderHomeItems reorders within sections without moving between them`() {
        val result = doc().reorderHomeItems(listOf("c", "b", "a", "d"))

        assertEquals(listOf("c", "b", "a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("d"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `reorderHomeItems ignores ids that are not on the home screen`() {
        val result = doc().reorderHomeItems(listOf("c", "b", "a", "unknown"))

        assertEquals(listOf("c", "b", "a"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `addSection appends a new section`() {
        val result = doc().addSection("s3", "Three")

        assertEquals(3, result.sections.size)
        assertEquals("s3", result.sections[2].id)
        assertEquals("Three", result.sections[2].title)
    }

    @Test
    fun `renameSection sets and clears the title`() {
        assertEquals("Uno", doc().renameSection("s1", "Uno").sections[0].title)
        assertNull(doc().renameSection("s1", null).sections[0].title)
        assertNull(doc().renameSection("s1", "  ").sections[0].title)
    }

    @Test
    fun `removeSection deletes it`() {
        val result = doc().removeSection("s1")

        assertEquals(listOf("s2"), result.sections.map { it.id })
    }

    @Test
    fun `moveSection reorders sections`() {
        val result = doc().moveSection("s2", toIndex = 0)

        assertEquals(listOf("s2", "s1"), result.sections.map { it.id })
    }

    @Test
    fun `setHiddenFromDrawer adds and removes`() {
        val hidden = doc().setHiddenFromDrawer("p.a", true)
        assertEquals(listOf("p.a"), hidden.hiddenFromDrawer)

        assertTrue(hidden.setHiddenFromDrawer("p.a", false).hiddenFromDrawer.isEmpty())
    }

    @Test
    fun `setAutoPruneUninstalled toggles the flag`() {
        assertEquals(true, doc().setAutoPruneUninstalled(true).autoPruneUninstalled)
    }

    @Test
    fun `updateTheme transforms the theme`() {
        val result = doc().updateTheme { it.copy(listTextSizeSp = 42f) }

        assertEquals(42f, result.theme.listTextSizeSp, 0.001f)
    }

    @Test
    fun `itemById and sectionById look up by id`() {
        assertEquals("p.a", doc().itemById("a")?.packageName)
        assertEquals("One", doc().sectionById("s1")?.title)
        assertNull(doc().itemById("nope"))
        assertNull(doc().sectionById("nope"))
    }
}
