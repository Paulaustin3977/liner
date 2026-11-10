package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The flat home order after a drag.
 *
 * The index the drag maths produces counts the slots of the list *without*
 * the dragged item, so the item lands in the slot the insertion indicator
 * showed — the test pins that count.
 */
class HomeOrderTest {

    private val order = listOf("a", "b", "c", "d")

    @Test
    fun `the dragged item moves down to the slot it was dropped on`() {
        assertEquals(
            listOf("a", "c", "b", "d"),
            homeOrderWithMovedItem(order, draggedId = "b", index = 2),
        )
    }

    @Test
    fun `the dragged item moves up to the slot it was dropped on`() {
        assertEquals(
            listOf("b", "a", "c", "d"),
            homeOrderWithMovedItem(order, draggedId = "b", index = 0),
        )
    }

    @Test
    fun `the dragged item lands at the end`() {
        assertEquals(
            listOf("a", "b", "d", "c"),
            homeOrderWithMovedItem(order, draggedId = "c", index = 3),
        )
    }

    @Test
    fun `the dragged item keeps its slot when it is dropped where it was`() {
        assertEquals(
            order,
            homeOrderWithMovedItem(order, draggedId = "b", index = 1),
        )
    }

    @Test
    fun `an index past the end lands the item at the end`() {
        assertEquals(
            listOf("b", "c", "d", "a"),
            homeOrderWithMovedItem(order, draggedId = "a", index = 99),
        )
    }

    @Test
    fun `a negative index lands the item at the front`() {
        assertEquals(
            listOf("d", "a", "b", "c"),
            homeOrderWithMovedItem(order, draggedId = "d", index = -1),
        )
    }

    @Test
    fun `an id that is not on the home screen leaves the order alone`() {
        assertEquals(
            order,
            homeOrderWithMovedItem(order, draggedId = "zzz", index = 0),
        )
    }

    @Test
    fun `the new order reorders the items inside their own section`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    items = listOf(
                        HomeItem(id = "a", packageName = "p.a"),
                        HomeItem(id = "b", packageName = "p.b"),
                    ),
                ),
                Section(
                    id = "s2",
                    items = listOf(
                        HomeItem(id = "c", packageName = "p.c"),
                        HomeItem(id = "d", packageName = "p.d"),
                    ),
                ),
            ),
        )

        val newOrder = homeOrderWithMovedItem(listOf("a", "b", "c", "d"), draggedId = "a", index = 1)
        val result = document.reorderHomeItems(newOrder)

        assertEquals(listOf("b", "a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("c", "d"), result.sections[1].items.map { it.id })
    }
}
