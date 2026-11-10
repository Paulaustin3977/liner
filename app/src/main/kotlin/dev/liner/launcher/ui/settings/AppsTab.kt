package dev.liner.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.ItemBounds
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addItem
import dev.liner.launcher.model.addSection
import dev.liner.launcher.model.homeOrderWithMovedItem
import dev.liner.launcher.model.moveItem
import dev.liner.launcher.model.removeItem
import dev.liner.launcher.model.reorderHomeItems
import dev.liner.launcher.model.sectionById
import dev.liner.launcher.model.setHiddenFromDrawer
import dev.liner.launcher.ui.common.SectionChooserSheet
import dev.liner.launcher.ui.common.dragIndicatorTop
import dev.liner.launcher.ui.home.EditModeController
import java.util.UUID

/**
 * The section the reorder list gives every row.
 *
 * The list is the flat home order, so the sections do not matter to the drag
 * maths — one synthetic section keeps every row in the same list.
 */
private const val FLAT_SECTION = "home-order"

/**
 * The apps tab: every installed app with a checkbox for the home screen, the
 * order of the home screen above them, and the section each app is in.
 *
 * A checked app is on the home screen; an unchecked one is not. The button
 * beside the name hides the app from the drawer (and shows it again), and the
 * one next to that moves the app to another section. Every change goes
 * through [onUpdate], which is the only thing that writes.
 */
@Composable
fun AppsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
    onRefresh: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // The home item the section chooser is open for.
    var movingItemId by remember { mutableStateOf<String?>(null) }

    val installedByPackage = remember(installedApps) {
        installedApps.associateBy { it.packageName }
    }
    // The home items in home order, each with the section it is in.
    val homeEntries = remember(document, installedByPackage) {
        AppCatalog.homeEntries(document, installedByPackage)
    }
    val homeByPackage = remember(homeEntries) {
        homeEntries.associateBy { it.item.packageName }
    }

    val visible = remember(installedApps, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { it.systemLabel.contains(trimmed, ignoreCase = true) }
        }
    }

    // The checkbox and the name do the same thing, so the decision is made
    // once — and it is made against the document as it is NOW: an edit that
    // has not been recomposed yet must not be undone by a stale copy.
    fun toggleHome(packageName: String) {
        onUpdate { doc ->
            val onHome = doc.sections.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { it.packageName == packageName }
            if (onHome != null) {
                doc.removeItem(onHome.id)
            } else {
                // The home screen needs a section to hold the app, so one is
                // created on demand when the document has none.
                val sectionId = doc.sections.firstOrNull()?.id ?: "default-${UUID.randomUUID()}"
                val withSection = if (doc.sections.isEmpty()) doc.addSection(sectionId, null) else doc
                withSection.addItem(
                    item = HomeItem(
                        id = UUID.randomUUID().toString(),
                        packageName = packageName,
                    ),
                    sectionId = sectionId,
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search apps") },
                modifier = Modifier.weight(1f),
            )
            // The package manager is queried again: an app installed while
            // the settings were open is not in the list yet.
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }

        Text(
            text = "Checked apps appear on your home screen",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            item(key = "home-order") {
                HomeOrderBlock(
                    entries = homeEntries,
                    onReorder = { newOrder -> onUpdate { doc -> doc.reorderHomeItems(newOrder) } },
                )
            }
            item(key = "all-apps") {
                Text(
                    text = "All apps",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(visible, key = { it.packageName }) { app ->
                val home = homeByPackage[app.packageName]
                val isHidden = app.packageName in document.hiddenFromDrawer

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = home != null,
                        onCheckedChange = { toggleHome(app.packageName) },
                    )
                    Text(
                        text = app.systemLabel,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { toggleHome(app.packageName) },
                    )
                    // Only an app that is on the home screen is in a section,
                    // so only it can be moved to one.
                    if (home != null) {
                        TextButton(onClick = { movingItemId = home.item.id }) {
                            Text("Move to section")
                        }
                    }
                    TextButton(
                        onClick = {
                            onUpdate { doc ->
                                doc.setHiddenFromDrawer(
                                    app.packageName,
                                    app.packageName !in doc.hiddenFromDrawer,
                                )
                            }
                        },
                    ) {
                        Text(if (isHidden) "Show" else "Hide")
                    }
                }
            }
        }

        Text(
            text = "Home order: ${homeEntries.size} apps",
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    // The chooser of the section the app is moved to. It leaves out the
    // section the app is already in.
    val movingId = movingItemId
    val movingSection = movingId?.let { id ->
        document.sections.firstOrNull { section -> section.items.any { it.id == id } }
    }
    if (movingId != null && movingSection != null) {
        SectionChooserSheet(
            sections = document.sections.map { it.id to it.title },
            currentSectionId = movingSection.id,
            onSelect = { sectionId ->
                onUpdate { doc ->
                    doc.moveItem(
                        movingId,
                        sectionId,
                        doc.sectionById(sectionId)?.items?.size ?: 0,
                    )
                }
            },
            onDismiss = { movingItemId = null },
        )
    }
}

/**
 * The order of the home screen, with the apps that are on it.
 *
 * A long press starts a drag. The list is not reordered while the finger
 * moves — the dragged app keeps its slot and an insertion indicator shows
 * where it would land — and the new order is committed once, when the finger
 * is released.
 */
@Composable
private fun HomeOrderBlock(
    entries: List<HomeEntry>,
    onReorder: (List<String>) -> Unit,
) {
    val editController = remember { EditModeController() }
    // The flat home order, and the section every row belongs to for the drag
    // maths: the list is one list, so the sections do not matter here.
    val sectionOrder = remember { listOf(FLAT_SECTION) }
    val order = remember(entries) { entries.map { it.item.id } }
    // The drag callback is built once, so it reads the order as it is now
    // rather than the copy it was built with.
    val currentOrder by rememberUpdatedState(order)
    val currentReorder by rememberUpdatedState(onReorder)

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "Home screen order",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Long-press an app to move it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    text = "No apps on your home screen yet",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entries.forEach { entry ->
                HomeOrderRow(
                    entry = entry,
                    editController = editController,
                    sectionOrder = sectionOrder,
                    onDrop = { index ->
                        currentReorder(homeOrderWithMovedItem(currentOrder, entry.item.id, index))
                    },
                )
            }
        }

        // The insertion indicator: a line at the slot the dragged app would
        // land in. It floats over the rows, so it is drawn after them. Its Y
        // is in the list's own coordinates — the same ones the drag maths
        // works in.
        val dragTarget = editController.dragTarget
        if (dragTarget != null) {
            val indicatorTop = dragIndicatorTop(
                target = dragTarget,
                bounds = editController.bounds.values.toList(),
                draggedId = editController.draggingId,
            )
            Box(
                Modifier
                    .offset { IntOffset(0, indicatorTop.toInt()) }
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * One app of the home screen order, reorderable by a long press.
 *
 * The row's position is recorded in the list's own coordinates — the ones
 * the drag maths and the insertion indicator use — so the row does not have
 * to know where the list is on the screen.
 */
@Composable
private fun HomeOrderRow(
    entry: HomeEntry,
    editController: EditModeController,
    sectionOrder: List<String>,
    onDrop: (Int) -> Unit,
) {
    // The row's origin in the list. The pointer's position is relative to
    // the row, so the two together give the finger's place in the list.
    var rowOrigin by remember(entry.item.id) { mutableStateOf(Offset.Zero) }

    // A row that leaves the list must not be counted by the drag maths.
    DisposableEffect(entry.item.id) {
        onDispose { editController.bounds.remove(entry.item.id) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInParent()
                rowOrigin = origin
                editController.bounds[entry.item.id] = ItemBounds(
                    id = entry.item.id,
                    sectionId = FLAT_SECTION,
                    top = origin.y,
                    bottom = origin.y + coordinates.size.height,
                )
            }
            .pointerInput(entry.item.id) {
                // The long press is detected here rather than by a
                // combinedClickable: the long press of a clickable consumes
                // the rest of the pointer stream, which would cancel the
                // drag it is meant to start.
                detectDragGesturesAfterLongPress(
                    onDragStart = { editController.startDrag(entry.item.id) },
                    onDrag = { change, _ ->
                        change.consume()
                        val finger = rowOrigin + change.position
                        editController.updateDrag(finger.y, sectionOrder)
                    },
                    onDragEnd = {
                        // The one commit of the drag: the order is written
                        // once, when the finger is released.
                        val target = editController.endDrag()
                        editController.exitEditMode()
                        if (target is DragTarget.InSection) {
                            onDrop(target.index)
                        }
                    },
                    onDragCancel = {
                        editController.endDrag()
                        editController.exitEditMode()
                    },
                )
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.displayLabel,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.sectionTitle ?: "Untitled",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
