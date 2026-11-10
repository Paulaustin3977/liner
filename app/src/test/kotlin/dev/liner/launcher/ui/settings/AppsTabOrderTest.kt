package dev.liner.launcher.ui.settings

import dev.liner.launcher.model.DragTargeting
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.ItemBounds
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The apps tab's "Home screen order" list.
 *
 * The list resolves the drop target with the home screen's maths
 * ([DragTargeting.computeTarget]) and commits the home screen's move
 * ([moveItemToTarget]). The two are wired together here the way the tab
 * wires them, so the whole decision — where the app lands, and what the
 * document looks like afterwards — is pinned without a device.
 */
class AppsTabOrderTest {

    private val document = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "One",
                items = listOf(
                    HomeItem(id = "a", packageName = "p.a"),
                    HomeItem(id = "b", packageName = "p.b"),
                ),
            ),
            Section(
                id = "s2",
                title = "Two",
                items = listOf(HomeItem(id = "c", packageName = "p.c")),
            ),
        ),
    )

    // The rows of the list in the list's own coordinates: the apps of the
    // first section, then the apps of the second.
    private val bounds = listOf(
        ItemBounds("a", "s1", top = 100f, bottom = 140f),
        ItemBounds("b", "s1", top = 150f, bottom = 190f),
        ItemBounds("c", "s2", top = 210f, bottom = 250f),
    )

    private val sectionOrder = listOf("s1", "s2")

    /** A finished drag: the target comes from the home screen's maths. */
    private fun drop(draggedId: String, dragY: Float): LayoutDocument {
        val target = DragTargeting.computeTarget(dragY, bounds, draggedId, sectionOrder)
            ?: return document
        return moveItemToTarget(document, draggedId, target)
    }

    @Test
    fun `a drag across a section boundary moves the app into the section above`() {
        // The finger is above the first app of the second section, so the
        // app belongs to the section above it.
        val result = drop("c", dragY = 200f)

        assertEquals(listOf("a", "b", "c"), result.sections[0].items.map { it.id })
        assertEquals(emptyList<String>(), result.sections[1].items.map { it.id })
    }

    @Test
    fun `a drag inside a section reorders the apps of that section`() {
        // Above the first app of the first section: the app goes to the top
        // of its own section, because there is no section above it.
        val result = drop("b", dragY = 90f)

        assertEquals(listOf("b", "a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("c"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `a drag that stays put leaves the document alone`() {
        val result = drop("a", dragY = 10f)

        assertEquals(document, result)
    }
}
