package dev.liner.launcher.model

/**
 * The flat home order with [draggedId] moved to [index].
 *
 * [flatOrder] is the flat order of the home item ids — every section's items,
 * in section order. [index] counts the slots of the list *without* the
 * dragged item, which is the count the drag maths works in, so the item lands
 * in the slot the insertion indicator showed.
 *
 * The order says nothing about the sections: the items keep the sections they
 * are in, so this only reorders the items inside a section.
 */
fun homeOrderWithMovedItem(
    flatOrder: List<String>,
    draggedId: String,
    index: Int,
): List<String> {
    if (draggedId !in flatOrder) return flatOrder
    val withoutItem = flatOrder.filterNot { it == draggedId }
    val safeIndex = index.coerceIn(0, withoutItem.size)
    return withoutItem.toMutableList().apply { add(safeIndex, draggedId) }
}
