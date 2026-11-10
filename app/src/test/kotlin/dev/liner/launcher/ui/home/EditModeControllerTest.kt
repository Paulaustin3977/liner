package dev.liner.launcher.ui.home

import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.ItemBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditModeControllerTest {

    private val sectionOrder = listOf("s1", "s2")

    private fun controllerWithBounds(): EditModeController {
        val controller = EditModeController()
        controller.bounds["a"] = ItemBounds("a", "s1", top = 100f, bottom = 140f)
        controller.bounds["b"] = ItemBounds("b", "s1", top = 150f, bottom = 190f)
        controller.bounds["c"] = ItemBounds("c", "s2", top = 210f, bottom = 250f)
        return controller
    }

    @Test
    fun `a fresh controller is not editing and drags nothing`() {
        val controller = EditModeController()

        assertFalse(controller.active)
        assertNull(controller.draggingId)
        assertNull(controller.dragTarget)
        assertTrue(controller.bounds.isEmpty())
    }

    @Test
    fun `starting a drag enters edit mode and remembers the row`() {
        val controller = EditModeController()

        controller.startDrag("a")

        assertTrue(controller.active)
        assertEquals("a", controller.draggingId)
        assertNull(controller.dragTarget)
    }

    @Test
    fun `the drag target is computed from the recorded bounds`() {
        val controller = controllerWithBounds()

        controller.startDrag("a")
        controller.updateDrag(180f, sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), controller.dragTarget)
    }

    @Test
    fun `updating the drag without a drag in progress does nothing`() {
        val controller = controllerWithBounds()

        controller.updateDrag(180f, sectionOrder)

        assertNull(controller.dragTarget)
    }

    @Test
    fun `ending a drag returns the target and clears the drag state`() {
        val controller = controllerWithBounds()
        controller.startDrag("a")
        controller.updateDrag(180f, sectionOrder)

        val target = controller.endDrag()

        assertEquals(DragTarget.InSection("s1", 1), target)
        assertNull(controller.draggingId)
        assertNull(controller.dragTarget)
        // Ending a drag does not leave edit mode.
        assertTrue(controller.active)
    }

    @Test
    fun `clearing the target leaves the drag in progress`() {
        val controller = controllerWithBounds()
        controller.startDrag("a")
        controller.updateDrag(180f, sectionOrder)

        controller.clearTarget()

        assertNull(controller.dragTarget)
        assertEquals("a", controller.draggingId)
    }

    @Test
    fun `leaving edit mode keeps the recorded bounds`() {
        val controller = controllerWithBounds()
        controller.startDrag("a")

        controller.exitEditMode()

        assertFalse(controller.active)
        assertNull(controller.draggingId)
        assertNull(controller.dragTarget)
        // The bounds are filled in by the layout that placed the rows, and
        // that layout does not run again just because edit mode ended.
        assertEquals(3, controller.bounds.size)
    }

    @Test
    fun `clearing the controller forgets the bounds as well`() {
        val controller = controllerWithBounds()
        controller.startDrag("a")

        controller.clear()

        assertFalse(controller.active)
        assertNull(controller.draggingId)
        assertNull(controller.dragTarget)
        assertTrue(controller.bounds.isEmpty())
    }
}
