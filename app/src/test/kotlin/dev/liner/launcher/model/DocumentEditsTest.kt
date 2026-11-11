package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `moveItem puts the item in the first section with a matching id`() {
        // A hand-edited layout can repeat a section id. The item must land
        // in the first of the two, not in both of them.
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "One", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s1", title = "Also one", items = listOf(HomeItem(id = "b", packageName = "p.b"))),
                Section(id = "s2", items = listOf(HomeItem(id = "c", packageName = "p.c"))),
            ),
        )

        val result = document.moveItem("c", toSectionId = "s1", toIndex = 0)

        assertEquals(listOf("c", "a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("b"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `moveItem removes every copy of the item`() {
        // A hand-edited layout can repeat an item id. The copy in the other
        // section must go with the one that is moved.
        val document = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    items = listOf(
                        HomeItem(id = "a", packageName = "p.a"),
                        HomeItem(id = "b", packageName = "p.b"),
                    ),
                ),
                Section(id = "s2", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val result = document.moveItem("a", toSectionId = "s2", toIndex = 0)

        assertEquals(listOf("b"), result.sections[0].items.map { it.id })
        assertEquals(listOf("a"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `moveItem removes the item from every section that holds it`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s2", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s3", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val result = document.moveItem("a", toSectionId = "s3", toIndex = 0)

        assertTrue(result.sections[0].items.isEmpty())
        assertTrue(result.sections[1].items.isEmpty())
        assertEquals(listOf("a"), result.sections[2].items.map { it.id })
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
    fun `an undone removal puts the item back where it was`() {
        val before = doc()
        val item = before.sections[0].items[1]
        val removed = before.removeItem(item.id)

        val restored = removed.addItem(item, sectionId = "s1", index = 1)

        assertEquals(listOf("a", "b", "c"), restored.sections[0].items.map { it.id })
        assertEquals(before, restored)
    }

    @Test
    fun `an undone removal keeps the item's custom label`() {
        val renamed = doc().renameItem("b", "Mail")
        val item = renamed.sections[0].items[1]

        val restored = renamed.removeItem("b").addItem(item, sectionId = "s1", index = 1)

        assertEquals("Mail", restored.sections[0].items[1].label)
    }

    @Test
    fun `an undone removal cannot add the item twice`() {
        val before = doc()
        val item = before.sections[0].items[1]

        // The item is still there: addItem refuses a duplicate id.
        val restored = before.addItem(item, sectionId = "s1", index = 1)

        assertEquals(before, restored)
    }

    @Test
    fun `an undone removal into a deleted section changes nothing`() {
        val removed = doc().removeItem("b").removeSection("s1")
        val item = doc().sections[0].items[1]

        assertEquals(removed, removed.addItem(item, sectionId = "s1", index = 1))
    }

    @Test
    fun `reorderHomeItems reorders within sections without moving between them`() {
        val result = doc().reorderHomeItems(listOf("c", "b", "a", "d"))

        assertEquals(listOf("c", "b", "a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("d"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `reorderHomeItems keeps items in their own sections`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s2", items = listOf(HomeItem(id = "b", packageName = "p.b"))),
            ),
        )

        val result = document.reorderHomeItems(listOf("b", "a"))

        assertEquals(listOf("a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("b"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `reorderHomeItems keeps unlisted items in their previous relative order`() {
        // Ids are deliberately in reverse alphabetical order so that sorting by
        // id and sorting by previous position give different answers.
        val document = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    items = listOf(
                        HomeItem(id = "z", packageName = "p.z"),
                        HomeItem(id = "y", packageName = "p.y"),
                        HomeItem(id = "x", packageName = "p.x"),
                    ),
                ),
            ),
        )

        val result = document.reorderHomeItems(listOf("z"))

        assertEquals(listOf("z", "y", "x"), result.sections[0].items.map { it.id })
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
    fun `addSection refuses a duplicate id`() {
        // A hand-edited layout can already hold this id. Two sections with
        // the same id would show the same apps, so the duplicate is refused.
        val before = doc()

        val result = before.addSection("s1", "Another one")

        assertEquals(before, result)
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
    fun `dismissFirstRunPrompt records the dismissal`() {
        val before = doc()
        assertFalse(before.firstRunPromptDismissed)

        val result = before.dismissFirstRunPrompt()

        assertTrue(result.firstRunPromptDismissed)
    }

    @Test
    fun `dismissFirstRunPrompt changes nothing else`() {
        val before = doc()

        val result = before.dismissFirstRunPrompt()

        assertEquals(before.sections, result.sections)
        assertEquals(before.theme, result.theme)
        assertEquals(before.hiddenFromDrawer, result.hiddenFromDrawer)
    }

    @Test
    fun `pruneUninstalledApps removes nothing while auto-pruning is off`() {
        val before = doc().setAutoPruneUninstalled(false)

        val result = before.pruneUninstalledApps(setOf("p.a", "p.b"))

        assertEquals(before, result)
        assertEquals(listOf("a", "b", "c"), result.sections[0].items.map { it.id })
        assertEquals(listOf("d"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `pruneUninstalledApps removes only the apps that are gone`() {
        val before = doc().setAutoPruneUninstalled(true)

        val result = before.pruneUninstalledApps(setOf("p.a", "p.c", "p.d"))

        // The surviving items keep their order and their section.
        assertEquals(listOf("a", "c"), result.sections[0].items.map { it.id })
        assertEquals(listOf("d"), result.sections[1].items.map { it.id })
        assertEquals(listOf("s1", "s2"), result.sections.map { it.id })
    }

    @Test
    fun `pruneUninstalledApps removes an app from the section it sits in`() {
        val before = doc().setAutoPruneUninstalled(true)

        val result = before.pruneUninstalledApps(setOf("p.a", "p.b", "p.c"))

        assertEquals(listOf("a", "b", "c"), result.sections[0].items.map { it.id })
        assertTrue(result.sections[1].items.isEmpty())
    }

    @Test
    fun `pruneUninstalledApps changes nothing when nothing is stale`() {
        val before = doc().setAutoPruneUninstalled(true)

        val result = before.pruneUninstalledApps(setOf("p.a", "p.b", "p.c", "p.d"))

        assertEquals(before, result)
    }

    @Test
    fun `pruneUninstalledApps on an empty installed list would empty the home screen`() {
        // This is what the caller must never do: an empty list is not
        // evidence that every app was uninstalled. The trust check in
        // AppListProvider is what keeps it from ever getting here.
        val before = doc().setAutoPruneUninstalled(true)

        val result = before.pruneUninstalledApps(emptySet())

        assertTrue(result.sections.all { it.items.isEmpty() })
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
