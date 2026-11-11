package dev.liner.launcher.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.DragTargeting
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.ItemBounds
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.moveItem
import dev.liner.launcher.model.sectionById

/**
 * Where a drop lands, expressed against a row that is on screen.
 *
 * The drag maths only sees the rows the list has composed, so the slot it
 * computes counts the *visible* rows — and a visible row is not an index
 * into the section's items. The anchor names the visible row the slot sits
 * against, so the slot can be looked up in the document when the drop is
 * committed.
 */
internal sealed interface DropAnchor {
    /** The item lands immediately before the row with this id. */
    data class Before(val itemId: String) : DropAnchor

    /** The item lands immediately after the row with this id. */
    data class After(val itemId: String) : DropAnchor
}

/**
 * The index the dragged item is inserted at, in the target section's full
 * post-removal item list.
 *
 * The slot the drag maths found counts the rows that are on screen, so it
 * cannot be used as an index into the section's items: in a scrolled list it
 * would put the item at the top of the section, far away from the slot the
 * indicator showed. The anchor names the row the slot sits against, and the
 * document says where that row is.
 *
 * Returns null when the anchor is not in the section — the slot cannot be
 * resolved, and the item is left where it is.
 */
internal fun resolveDropIndex(
    sectionItems: List<HomeItem>,
    draggedId: String,
    anchor: DropAnchor,
): Int? {
    // The item is inserted into the list without itself, exactly as
    // `moveItem` does it.
    val remaining = sectionItems.filter { it.id != draggedId }
    return when (anchor) {
        is DropAnchor.Before -> {
            if (anchor.itemId == draggedId) {
                // The slot is the dragged row's own slot — it is the only row
                // of the section on screen — so the item stays where it is.
                val current = sectionItems.indexOfFirst { it.id == draggedId }
                if (current < 0) null else current.coerceIn(0, remaining.size)
            } else {
                val index = remaining.indexOfFirst { it.id == anchor.itemId }
                if (index < 0) null else index
            }
        }
        is DropAnchor.After -> {
            val index = remaining.indexOfFirst { it.id == anchor.itemId }
            if (index < 0) null else (index + 1).coerceAtMost(remaining.size)
        }
    }
}

/**
 * The document with the dragged item moved to the slot the insertion
 * indicator showed.
 *
 * A slot inside a section is resolved against the [anchor] — the visible row
 * the indicator was drawn at — because the slot the drag maths found counts
 * the rows that are on screen and not the rows of the section. A slot in the
 * section above is the end of that section.
 *
 * The document is returned unchanged when the slot cannot be resolved.
 */
internal fun commitDropToDocument(
    document: LayoutDocument,
    itemId: String,
    target: DragTarget,
    anchor: DropAnchor?,
): LayoutDocument = when (target) {
    is DragTarget.InSection -> {
        val section = document.sectionById(target.sectionId)
        val index = if (section == null || anchor == null) {
            null
        } else {
            resolveDropIndex(section.items, itemId, anchor)
        }
        if (index == null) document else document.moveItem(itemId, target.sectionId, index)
    }
    is DragTarget.PreviousSection -> document.moveItem(
        itemId,
        target.sectionId,
        document.sectionById(target.sectionId)?.items?.size ?: 0,
    )
}

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

    /**
     * The visible row the drop would land against.
     *
     * It is the row the insertion indicator is drawn at, so the commit can
     * put the item in the slot the indicator showed. It is null when there
     * is no drag in progress, and when the drop is not a slot inside a
     * section.
     */
    internal var dropAnchor by mutableStateOf<DropAnchor?>(null)
        private set

    val bounds = mutableStateMapOf<String, ItemBounds>()

    fun startDrag(id: String) {
        active = true
        draggingId = id
        dragTarget = null
        dropAnchor = null
    }

    fun updateDrag(dragY: Float, sectionOrder: List<String>) {
        val id = draggingId ?: return
        val dragged = bounds[id] ?: return
        // The slot is inside the dragged row's own section, so only that
        // section's rows take part in the maths. The rows that scrolled out
        // of the list are not in the bounds at all — which is why the slot
        // is resolved against a visible row instead of being counted.
        val sectionRows = bounds.values
            .filter { it.sectionId == dragged.sectionId }
            .sortedBy { it.top }
        dragTarget = DragTargeting.computeTarget(dragY, sectionRows, id, sectionOrder)
        dropAnchor = anchorFor(dragTarget, sectionRows, id)
    }

    /**
     * The visible row the slot sits against.
     *
     * The insertion indicator is drawn from the same rows: a slot before the
     * n-th visible row is drawn at that row's top edge, and a slot past
     * every visible row is drawn under the last one.
     */
    private fun anchorFor(
        target: DragTarget?,
        sectionRows: List<ItemBounds>,
        draggedId: String,
    ): DropAnchor? = when (target) {
        is DragTarget.InSection -> {
            // The siblings are the rows of the section without the dragged
            // one — the same list the drag maths counted.
            val siblings = sectionRows.filter { it.id != draggedId }
            when {
                // The dragged row is the only row of the section on screen:
                // the indicator sits at its own top edge, so the item stays
                // where it is.
                siblings.isEmpty() -> DropAnchor.Before(draggedId)
                target.index < siblings.size -> DropAnchor.Before(siblings[target.index].id)
                else -> DropAnchor.After(siblings.last().id)
            }
        }
        // The item moves into the section above, at its end: there is no row
        // of this section to land against.
        is DragTarget.PreviousSection -> null
        null -> null
    }

    /** Returns the computed target and clears the drag state. */
    fun endDrag(): DragTarget? {
        val target = dragTarget
        draggingId = null
        dragTarget = null
        dropAnchor = null
        return target
    }

    /**
     * Drops the computed insertion target. Used while the drag is over the
     * trash zone, where the item is removed rather than moved, so there is no
     * slot to show.
     */
    fun clearTarget() {
        dragTarget = null
        dropAnchor = null
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
        dropAnchor = null
    }

    fun clear() {
        exitEditMode()
        bounds.clear()
    }
}
