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

    /**
     * Move into [sectionId] — the section immediately before the dragged item's —
     * appended at the end of that section's items.
     */
    data class PreviousSection(val sectionId: String) : DragTarget
}

object DragTargeting {
    /**
     * Works out where the dragged item would land if released at [dragY].
     *
     * [bounds] must be the on-screen rectangles of the home items, and
     * [sectionOrder] the document's section ids in order. Every item in
     * [bounds] must belong to a section in [sectionOrder]. The order of
     * [bounds] does not matter: the computation sorts by [ItemBounds.top].
     *
     * Returns null when the dragged item is not in [bounds].
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

        // Sorted by top so the result does not depend on the caller's order.
        val sectionItems = bounds
            .filter { it.sectionId == dragged.sectionId }
            .sortedBy { it.top }

        // The section's first item counts even when it is the dragged item
        // itself: dragging a section's top item above its own top edge means
        // moving it into the section above. sectionItems always contains the
        // dragged item, so it is never empty.
        val firstTop = sectionItems.first().top
        if (dragY < firstTop && previousSectionId != null) {
            return DragTarget.PreviousSection(previousSectionId)
        }

        // The insertion index is counted with the dragged item already removed.
        val siblings = sectionItems.filter { it.id != draggedId }
        var index = 0
        for (bound in siblings) {
            // Landing on the midpoint counts as past it, so the item goes below.
            if (dragY >= (bound.top + bound.bottom) / 2f) index++ else break
        }

        return DragTarget.InSection(dragged.sectionId, index)
    }
}
