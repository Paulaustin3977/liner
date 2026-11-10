package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragTargetingTest {

    private val bounds = listOf(
        ItemBounds("a", "s1", top = 100f, bottom = 140f),
        ItemBounds("b", "s1", top = 150f, bottom = 190f),
        ItemBounds("c", "s2", top = 210f, bottom = 250f),
    )

    private val sectionOrder = listOf("s1", "s2")

    @Test
    fun `an unknown dragged id returns null`() {
        assertNull(DragTargeting.computeTarget(120f, bounds, "nope", sectionOrder))
    }

    @Test
    fun `dragging above the first item of the first section targets index zero`() {
        val target = DragTargeting.computeTarget(95f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 0), target)
    }

    @Test
    fun `dragging past the midpoint of an item moves below it`() {
        val target = DragTargeting.computeTarget(180f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `the dragged item is excluded from the index computation`() {
        // At y=170 — the midpoint of "b" — the answer is index 1 within the
        // remaining sibling list [a]. Counting "b" as its own sibling would
        // give index 2, which no longer exists in a two-item section.
        val target = DragTargeting.computeTarget(170f, bounds, "b", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `a drop exactly on an item's midpoint lands below it`() {
        // At y=120 — exactly the midpoint of "a" — the item goes after "a",
        // not before it.
        val target = DragTargeting.computeTarget(120f, bounds, "b", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `dragging above the first item of a later section targets the previous section`() {
        val target = DragTargeting.computeTarget(200f, bounds, "c", sectionOrder)

        assertEquals(DragTarget.PreviousSection("s1"), target)
    }

    @Test
    fun `the first section cannot target a previous section`() {
        val target = DragTargeting.computeTarget(10f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 0), target)
    }
}
