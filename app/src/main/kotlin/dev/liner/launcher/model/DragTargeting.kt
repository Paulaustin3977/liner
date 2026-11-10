package dev.liner.launcher.model

/** An on-screen rectangle for one home item, in root coordinates. */
data class ItemBounds(
    val id: String,
    val sectionId: String,
    val top: Float,
    val bottom: Float,
)

sealed interface DragTarget {
    /** Insert into [sectionId] at [index], counted with the dragged item already removed. */
    data class InSection(val sectionId: String, val index: Int) : DragTarget

    /** Move into [sectionId], which is the section immediately before the dragged item's. */
    data class PreviousSection(val sectionId: String) : DragTarget
}

object DragTargeting {
    /**
     * Works out where the dragged item would land if released at [dragY].
     *
     * [bounds] must be the ordered list of on-screen item rectangles for the
     * current screen, and [sectionOrder] the document's section ids in order.
     * Returns null when the dragged item is not in [bounds].
     *
     * The dragged item keeps its slot while the drag runs; this only reports
     * the slot it would land in, so the UI can animate an insertion indicator
     * and commit the reorder on release.
     */
    fun computeTarget(
        dragY: Float,
        bounds: List<ItemBounds>,
        draggedId: String,
        sectionOrder: List<String>,
    ): DragTarget? {
        val dragged = bounds.firstOrNull { it.id == draggedId } ?: return null

        val sectionIndex = sectionOrder.indexOf(dragged.sectionId)
        val previousSectionId = if (sectionIndex > 0) sectionOrder[sectionIndex - 1] else null

        val sectionItems = bounds.filter { it.sectionId == dragged.sectionId }
        val siblings = sectionItems.filter { it.id != draggedId }

        // The section's first item counts even when it is the dragged item
        // itself: dragging a section's top item above its own top edge means
        // moving it into the section above.
        val firstTop = sectionItems.minOfOrNull { it.top }
        if (firstTop != null && dragY < firstTop && previousSectionId != null) {
            return DragTarget.PreviousSection(previousSectionId)
        }

        var index = 0
        for (bound in siblings) {
            // Landing on the midpoint counts as past it, so the item goes below.
            if (dragY >= (bound.top + bound.bottom) / 2f) index++ else break
        }

        return DragTarget.InSection(dragged.sectionId, index)
    }
}
