package dev.liner.launcher.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.DragTargeting
import dev.liner.launcher.model.ItemBounds

/**
 * Holds the state of an in-progress drag. The list is not reordered live —
 * the controller only computes where the item would land, and the caller
 * commits that on release.
 */
class EditModeController {

    var active by mutableStateOf(false)
        private set

    var draggingId by mutableStateOf<String?>(null)
        private set

    var dragTarget by mutableStateOf<DragTarget?>(null)
        private set

    val bounds = mutableStateMapOf<String, ItemBounds>()

    fun startDrag(id: String) {
        active = true
        draggingId = id
        dragTarget = null
    }

    fun updateDrag(dragY: Float, sectionOrder: List<String>) {
        val id = draggingId ?: return
        dragTarget = DragTargeting.computeTarget(dragY, bounds.values.toList(), id, sectionOrder)
    }

    /** Returns the computed target and clears the drag state. */
    fun endDrag(): DragTarget? {
        val target = dragTarget
        draggingId = null
        dragTarget = null
        return target
    }

    /**
     * Drops the computed insertion target. Used while the drag is over the
     * trash zone, where the item is removed rather than moved, so there is no
     * slot to show.
     */
    fun clearTarget() {
        dragTarget = null
    }

    /**
     * Leaves edit mode but keeps the recorded bounds: they are filled in by
     * the layout pass that placed the rows, and that pass does not run again
     * just because edit mode ended.
     */
    fun exitEditMode() {
        active = false
        draggingId = null
        dragTarget = null
    }

    fun clear() {
        exitEditMode()
        bounds.clear()
    }
}
