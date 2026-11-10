package dev.liner.launcher.ui.common

import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.ItemBounds

/**
 * The Y at which the insertion indicator is drawn for [target].
 *
 * The index of a [DragTarget.InSection] counts the dragged item as already
 * removed, so the dragged row must be left out of the sibling list — counting
 * it would put the indicator a row too low. A [DragTarget.PreviousSection]
 * drops the item at the *end* of that section, so its indicator belongs under
 * the section's last row rather than above its first.
 *
 * The home screen's drag and the settings Apps tab's reorder list both need
 * it, so it lives here rather than in `ui/home`.
 */
internal fun dragIndicatorTop(
    target: DragTarget,
    bounds: List<ItemBounds>,
    draggedId: String?,
): Float = when (target) {
    is DragTarget.InSection -> {
        val siblings = bounds
            .filter { it.sectionId == target.sectionId && it.id != draggedId }
            .sortedBy { it.top }
        if (target.index < siblings.size) {
            siblings[target.index].top
        } else {
            // The item lands at the end of the section: under its last row,
            // or — when it is the section's only row — where it already is.
            siblings.lastOrNull()?.bottom
                ?: bounds.firstOrNull { it.id == draggedId }?.top
                ?: 0f
        }
    }
    is DragTarget.PreviousSection -> {
        val previous = bounds
            .filter { it.sectionId == target.sectionId }
            .sortedBy { it.top }
        previous.lastOrNull()?.bottom
            // The previous section is empty, so there is no row to draw
            // under: the indicator goes where the dragged row came from.
            ?: bounds.firstOrNull { it.id == draggedId }?.top
            ?: 0f
    }
}
