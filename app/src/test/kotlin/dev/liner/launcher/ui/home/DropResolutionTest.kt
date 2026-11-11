package dev.liner.launcher.ui.home

import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.ItemBounds
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.moveItem
import dev.liner.launcher.model.sectionById
import dev.liner.launcher.ui.common.dragIndicatorTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A drag that is committed has to put the item where the insertion
 * indicator was drawn.
 *
 * The list is lazy: only the rows that are on screen are composed, and a row
 * that scrolls out of the list is dropped from the bounds the drag maths
 * uses. The slot the maths finds therefore counts the *visible* rows, and
 * it cannot be used as an index into the section's items — in a scrolled
 * list that would put the item at the top of the section, far away from the
 * slot the user was shown.
 *
 * The drop is therefore resolved against an anchor: the visible row the
 * indicator is drawn at. These tests pin the resolution end to end — from the
 * gesture maths, through the anchor, to the committed document.
 */
class DropResolutionTest {

    private val sectionId = "s1"

    private fun items(count: Int): List<HomeItem> = items("i", count)

    private fun items(prefix: String, count: Int): List<HomeItem> =
        (1..count).map { HomeItem(id = "$prefix$it", packageName = "com.app$prefix$it") }

    private val document = LayoutDocument(
        sections = listOf(Section(id = sectionId, title = "Daily", items = items(10))),
    )

    /**
     * The bounds of the rows that are on screen: i6 to i10 of a ten-row
     * section, as if the list were scrolled down. The rows are 40 tall and
     * 10 apart.
     */
    private fun visibleBounds(vararg ids: String): List<ItemBounds> =
        ids.mapIndexed { index, id ->
            val top = 100f + index * 50f
            ItemBounds(id = id, sectionId = sectionId, top = top, bottom = top + 40f)
        }

    private fun controllerWith(bounds: List<ItemBounds>): EditModeController {
        val controller = EditModeController()
        bounds.forEach { controller.bounds[it.id] = it }
        return controller
    }

    private fun sectionIds(doc: LayoutDocument): List<String> =
        doc.sectionById(sectionId)!!.items.map { it.id }

    @Test
    fun `a drop in a scrolled section lands where the indicator was drawn`() {
        // The list is scrolled: i1 to i5 are off the screen, so only i6 to
        // i10 are in the bounds. i8 is dragged down between i9 and i10.
        val bounds = visibleBounds("i6", "i7", "i8", "i9", "i10")
        val controller = controllerWith(bounds)

        controller.startDrag("i8")
        // The midpoint of i9 is 270, the midpoint of i10 is 320: 280 is
        // below i9 and above i10.
        controller.updateDrag(280f, listOf(sectionId))
        // The anchor is read before the drag ends: ending it clears the
        // anchor, the way the screen reads it.
        val anchor = controller.dropAnchor
        val target = controller.endDrag()!!

        // The slot the maths found counts the visible rows only.
        assertEquals(DragTarget.InSection(sectionId, 3), target)
        assertEquals(DropAnchor.Before("i10"), anchor)
        // The indicator is drawn at the top of i10 — the boundary between
        // i9 and i10.
        assertEquals(300f, dragIndicatorTop(target!!, bounds, "i8"), 0f)

        // The commit has to put the item in that slot: between i9 and i10,
        // and not at the top of the section.
        val moved = commitDropToDocument(document, "i8", target, anchor)

        assertEquals(
            listOf("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i9", "i8", "i10"),
            sectionIds(moved),
        )
        // The slot the maths found on its own would have put the item at
        // the top of the section.
        assertEquals(
            listOf("i1", "i2", "i3", "i8", "i4", "i5", "i6", "i7", "i9", "i10"),
            sectionIds(document.moveItem("i8", sectionId, 3)),
        )
    }

    @Test
    fun `the anchor names the visible row the indicator is drawn at`() {
        val bounds = visibleBounds("i6", "i7", "i8", "i9", "i10")
        val controller = controllerWith(bounds)

        controller.startDrag("i8")
        controller.updateDrag(280f, listOf(sectionId))

        // The slot is before i10, the fourth visible row without the
        // dragged one.
        assertEquals(DropAnchor.Before("i10"), controller.dropAnchor)
    }

    @Test
    fun `a drop below every visible row lands after the last one`() {
        val bounds = visibleBounds("i6", "i7", "i8", "i9", "i10")
        val controller = controllerWith(bounds)

        controller.startDrag("i8")
        // Past the midpoint of i10: the item would land at the end of the
        // visible rows.
        controller.updateDrag(330f, listOf(sectionId))

        assertEquals(DropAnchor.After("i10"), controller.dropAnchor)

        val moved = commitDropToDocument(
            document,
            "i8",
            DragTarget.InSection(sectionId, 4),
            controller.dropAnchor,
        )

        // i8 is the last row of the section.
        assertEquals(
            listOf("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i9", "i10", "i8"),
            sectionIds(moved),
        )
    }

    @Test
    fun `a fully visible section lands where it always did`() {
        // Every row is on screen, so the slot the maths finds is already an
        // index into the section's items. The drop is the same one as
        // above — between i9 and i10 — and it has to land in the same place.
        val bounds = visibleBounds("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9", "i10")
        val controller = controllerWith(bounds)

        controller.startDrag("i8")
        // The midpoint of i9 is 530, the midpoint of i10 is 580.
        controller.updateDrag(550f, listOf(sectionId))
        val anchor = controller.dropAnchor
        val target = controller.endDrag()!!

        assertEquals(DragTarget.InSection(sectionId, 8), target)
        assertEquals(DropAnchor.Before("i10"), anchor)

        val moved = commitDropToDocument(document, "i8", target, anchor)

        assertEquals(
            listOf("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i9", "i8", "i10"),
            sectionIds(moved),
        )
    }

    @Test
    fun `the item stays where it is when it is the only row on screen`() {
        // The dragged row is the only row of the section that is composed,
        // so the indicator sits at its own top edge: the item must not
        // move.
        val bounds = visibleBounds("i8")
        val controller = controllerWith(bounds)

        controller.startDrag("i8")
        controller.updateDrag(120f, listOf(sectionId))
        val anchor = controller.dropAnchor
        val target = controller.endDrag()!!

        assertEquals(DragTarget.InSection(sectionId, 0), target)
        assertEquals(DropAnchor.Before("i8"), anchor)
        assertEquals(100f, dragIndicatorTop(target!!, bounds, "i8"), 0f)

        val moved = commitDropToDocument(document, "i8", target, anchor)

        assertEquals(
            listOf("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9", "i10"),
            sectionIds(moved),
        )
    }

    @Test
    fun `an anchor that is not in the section is not resolved`() {
        val items = items(10)

        assertNull(resolveDropIndex(items, "i8", DropAnchor.Before("nope")))
        assertNull(resolveDropIndex(items, "i8", DropAnchor.After("nope")))
    }

    @Test
    fun `a drop without an anchor leaves the document alone`() {
        // The anchor is what makes the slot resolvable: without it there is
        // no way to say where the item should land.
        val moved = commitDropToDocument(
            document,
            "i8",
            DragTarget.InSection(sectionId, 3),
            null,
        )

        assertEquals(sectionIds(document), sectionIds(moved))
    }

    @Test
    fun `a drop into the section above lands at its end`() {
        val twoSections = document.copy(
            sections = listOf(
                Section(id = "s1", title = "Daily", items = items("a", 3)),
                Section(id = "s2", title = "Work", items = items("b", 3)),
            ),
        )

        // "b1" is the first row of the section below: dropping it above its
        // own section moves it to the end of the section above.
        val moved = commitDropToDocument(
            twoSections,
            "b1",
            DragTarget.PreviousSection("s1"),
            null,
        )

        assertEquals(
            listOf("a1", "a2", "a3", "b1"),
            moved.sectionById("s1")!!.items.map { it.id },
        )
        assertEquals(
            listOf("b2", "b3"),
            moved.sectionById("s2")!!.items.map { it.id },
        )
    }

    @Test
    fun `the anchor is cleared when the drag ends`() {
        val controller = controllerWith(visibleBounds("i6", "i7", "i8"))

        controller.startDrag("i8")
        controller.updateDrag(110f, listOf(sectionId))
        assertEquals(DropAnchor.Before("i6"), controller.dropAnchor)

        controller.endDrag()

        assertNull(controller.dropAnchor)
    }
}
