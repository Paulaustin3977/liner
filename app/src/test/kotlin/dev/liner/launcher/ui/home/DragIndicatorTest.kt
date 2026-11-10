package dev.liner.launcher.ui.home

import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.ItemBounds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The insertion indicator has to point at the slot the item would land in.
 * The index of a [DragTarget.InSection] counts the dragged row as already
 * removed, so the indicator must not count it twice.
 */
class DragIndicatorTest {

    private val bounds = listOf(
        ItemBounds("a", "s1", top = 100f, bottom = 140f),
        ItemBounds("b", "s1", top = 150f, bottom = 190f),
        ItemBounds("c", "s2", top = 210f, bottom = 250f),
    )

    @Test
    fun `the indicator sits at the top of the row the item would land above`() {
        val top = dragIndicatorTop(
            target = DragTarget.InSection("s1", 0),
            bounds = bounds,
            draggedId = "b",
        )

        assertEquals(100f, top, 0f)
    }

    @Test
    fun `the dragged row is left out of the sibling list`() {
        // "a" is the dragged row, so the remaining siblings of s1 are [b].
        // Landing at index 0 therefore points at the top of "b" — counting
        // the dragged row would point at the top of "a" instead.
        val top = dragIndicatorTop(
            target = DragTarget.InSection("s1", 0),
            bounds = bounds,
            draggedId = "a",
        )

        assertEquals(150f, top, 0f)
    }

    @Test
    fun `the indicator sits under the last sibling when the item lands at the end`() {
        // The remaining siblings of s1 are [b], so index 1 is the end of the
        // section: the line goes under "b".
        val top = dragIndicatorTop(
            target = DragTarget.InSection("s1", 1),
            bounds = bounds,
            draggedId = "a",
        )

        assertEquals(190f, top, 0f)
    }

    @Test
    fun `the indicator of a section's only row sits where that row is`() {
        // "c" is the only row of s2, so the item stays where it is and the
        // line belongs at the top of the row — not at the top of the screen.
        val top = dragIndicatorTop(
            target = DragTarget.InSection("s2", 0),
            bounds = bounds,
            draggedId = "c",
        )

        assertEquals(210f, top, 0f)
    }

    @Test
    fun `the indicator of a previous section sits under that section's last row`() {        // The item lands at the end of s1, so the line belongs under "b".
        val top = dragIndicatorTop(
            target = DragTarget.PreviousSection("s1"),
            bounds = bounds,
            draggedId = "c",
        )

        assertEquals(190f, top, 0f)
    }

    @Test
    fun `the indicator of an empty previous section sits where the row came from`() {
        val top = dragIndicatorTop(
            target = DragTarget.PreviousSection("s0"),
            bounds = bounds,
            draggedId = "c",
        )

        assertEquals(210f, top, 0f)
    }
}
