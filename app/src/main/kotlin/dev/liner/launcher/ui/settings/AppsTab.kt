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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.font.FontWeight
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
import dev.liner.launcher.model.moveItem
import dev.liner.launcher.model.removeItem
import dev.liner.launcher.model.sectionById
import dev.liner.launcher.model.setHiddenFromDrawer
import dev.liner.launcher.ui.common.SectionChooserSheet
import dev.liner.launcher.ui.common.dragIndicatorTop
import dev.liner.launcher.ui.home.EditModeController
import java.util.UUID

/**
 * The apps tab: every installed app with a checkbox for the home screen, the
 * order of the home screen above them, and the section each app is in.
 *
 * A checked app is on the home screen; an unchecked one is not. The button
 * beside the name hides the app from the drawer (and shows it again), and the
 * one next to that moves the app to another section. Every change goes
 * through [onUpdate], which is the only thing that writes.
 *
 * [appsLoaded] tells the tab whether the package manager has answered yet:
 * an empty list before that means "not loaded", not "no apps installed".
 */
@Composable
fun AppsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    appsLoaded: Boolean,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
    onRefresh: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // The home item the section chooser is open for.
    var movingItemId by remember { mutableStateOf<String?>(null) }
    // The home items that were taken off the home screen, by package name.
    // An app that is put back must keep the label and the style the user
    // gave it, so the item itself is kept — not just the knowledge that the
    // app was there.
    var removedItems by remember { mutableStateOf(emptyMap<String, RemovedHomeItem>()) }

    val installedByPackage = remember(installedApps) {
        installedApps.associateBy { it.packageName }
    }
    // The home items in home order, each with the section it is in.
    val homeEntries = remember(document, installedByPackage) {
        AppCatalog.homeEntries(document, installedByPackage)
    }
    val homeByPackage = remember(homeEntries) {
        // An app is on the home screen when *any* item carries its package:
        // a document can hold the same app in two sections.
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
        val remembered = removedItems[packageName]
        var removed: RemovedHomeItem? = null
        onUpdate { doc ->
            val result = toggleHomeApp(doc, packageName, remembered)
            removed = result.removed
            result.document
        }
        // The item that was taken off the home screen is remembered, so that
        // putting it back keeps the label and the style the user gave it.
        val taken = removed
        removedItems = if (taken != null) {
            removedItems + (packageName to taken)
        } else {
            removedItems - packageName
        }
    }

    // The one commit of a finished drag: the app moves to the section and the
    // slot the insertion indicator showed. It is the move the home screen
    // makes, so a drag that crosses a section boundary moves the app into
    // the other section instead of quietly doing nothing.
    fun commitMove(itemId: String, target: DragTarget) {
        onUpdate { doc -> moveItemToTarget(doc, itemId, target) }
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
                    document = document,
                    entries = homeEntries,
                    onMove = { itemId, target -> commitMove(itemId, target) },
                )
            }
            item(key = "all-apps") {
                Text(
                    text = "All apps",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            if (!appsLoaded) {
                // The package manager has not answered yet, so an empty list
                // means "still loading", not "no apps installed".
                item(key = "loading") {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
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
 * The move a finished drag commits.
 *
 * The target is the slot the insertion indicator showed. A
 * [DragTarget.InSection] is a slot inside a section — the app moves to that
 * slot, and stays in its section. A [DragTarget.PreviousSection] is the end
 * of the section above the dragged app, so the app moves *into* that
 * section: a drag that crosses a section boundary moves the app across, the
 * same way the home screen's drag does.
 */
internal fun moveItemToTarget(
    document: LayoutDocument,
    itemId: String,
    target: DragTarget,
): LayoutDocument = when (target) {
    is DragTarget.InSection -> document.moveItem(itemId, target.sectionId, target.index)
    is DragTarget.PreviousSection -> document.moveItem(
        itemId,
        target.sectionId,
        document.sectionById(target.sectionId)?.items?.size ?: 0,
    )
}

/**
 * A home item that was taken off the home screen, with the section it was in
 * and its place in it.
 *
 * The item itself is kept — not just the knowledge that the app was there —
 * because its label and its style belong to the user: a new item in its
 * place would silently drop both.
 */
internal data class RemovedHomeItem(
    val item: HomeItem,
    val sectionId: String,
    val index: Int,
)

/**
 * What a toggle of the home-screen checkbox did.
 *
 * [removed] is the item that was taken off the home screen, and is null when
 * the app was put on the home screen instead.
 */
internal data class HomeToggleResult(
    val document: LayoutDocument,
    val removed: RemovedHomeItem?,
)

/**
 * Toggles an app on and off the home screen.
 *
 * Unchecking takes **every** copy of the app off the home screen: a copy
 * left in a second section would put the checkbox straight back to *checked*
 * and leave the app on the home screen, so the control would look as if it
 * did nothing. The first copy — the one the user sees first — is remembered,
 * with the section it was in and its place in it.
 *
 * Checking puts the app back the way it was: the same item, in the same
 * section, at the same place, so the label and the style survive the round
 * trip. An app that was never on the home screen is added to the first
 * section as a new item, and a section is created on demand when the
 * document has none.
 */
internal fun toggleHomeApp(
    document: LayoutDocument,
    packageName: String,
    remembered: RemovedHomeItem?,
): HomeToggleResult {
    val copies = document.sections.flatMap { section ->
        section.items
            .filter { it.packageName == packageName }
            .map { item -> section.id to item }
    }
    if (copies.isNotEmpty()) {
        val (sectionId, item) = copies.first()
        val index = document.sectionById(sectionId)?.items?.indexOfFirst { it.id == item.id } ?: 0
        val without = copies.fold(document) { acc, (_, copy) -> acc.removeItem(copy.id) }
        return HomeToggleResult(without, RemovedHomeItem(item, sectionId, index))
    }

    // The app is not on the home screen. It goes back into the section it
    // was taken from — unless that section is gone, in which case the first
    // section takes it. The item is kept either way.
    val sectionId = remembered?.sectionId?.takeIf { id -> document.sections.any { it.id == id } }
        ?: document.sections.firstOrNull()?.id
        ?: "default-${UUID.randomUUID()}"
    val withSection = if (document.sections.none { it.id == sectionId }) {
        document.addSection(sectionId, null)
    } else {
        document
    }
    val item = remembered?.item
        ?: HomeItem(id = UUID.randomUUID().toString(), packageName = packageName)
    // The place the item had only means something in the section it had.
    val index = if (remembered != null && remembered.sectionId == sectionId) {
        remembered.index
    } else {
        Int.MAX_VALUE
    }
    return HomeToggleResult(withSection.addItem(item, sectionId, index), null)
}

/**
 * The order of the home screen, grouped by section exactly as the home screen
 * shows it: the apps of a section under its title, the sections in the
 * document's order.
 *
 * A long press starts a drag. The list is not reordered while the finger
 * moves — the dragged app keeps its slot and an insertion indicator shows
 * where it would land — and the move is committed once, when the finger is
 * released.
 *
 * The drop target comes from [dev.liner.launcher.model.DragTargeting], the
 * same maths the home screen uses, and the commit is the same `moveItem`
 * call, so a drag can cross a section boundary: dropping an app above a
 * section moves it into the section above.
 */
@Composable
private fun HomeOrderBlock(
    document: LayoutDocument,
    entries: List<HomeEntry>,
    onMove: (itemId: String, target: DragTarget) -> Unit,
) {
    val editController = remember { EditModeController() }
    // The document's section order. The drag maths needs the real sections —
    // one synthetic section for the whole list would refuse every drag that
    // crosses a section boundary.
    val sectionOrder = document.sections.map { it.id }
    // The move callback is read as it is now, not as it was when the row was
    // composed.
    val currentMove by rememberUpdatedState(onMove)

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "Home screen order",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Long-press an app to move it. Drag it up over a section title " +
                    "to move it into the section above.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    text = "No apps on your home screen yet",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            document.sections.forEachIndexed { sectionIndex, section ->
                // The apps of this section, in home order. The home screen
                // takes them the same way — a hand-edited layout can repeat
                // a section id, and then both sections show the same apps.
                val sectionEntries = entries
                    .filter { it.sectionId == section.id }
                    .distinctBy { it.item.id }
                // An empty section has nothing to show — the home screen
                // shows one neither.
                if (sectionEntries.isEmpty()) return@forEachIndexed
                Text(
                    text = section.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                sectionEntries.forEach { entry ->
                    // The section index keeps the key unique even when a
                    // hand-edited layout repeats a section id.
                    key("$sectionIndex:${entry.item.id}") {
                        HomeOrderRow(
                            entry = entry,
                            editController = editController,
                            sectionOrder = sectionOrder,
                            onDrop = { target -> currentMove(entry.item.id, target) },
                        )
                    }
                }
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
    onDrop: (DragTarget) -> Unit,
) {
    // The section order is read as it is now: a drag must see the sections
    // the document has, not the ones it had when the row was composed.
    val currentSectionOrder by rememberUpdatedState(sectionOrder)
    val currentDrop by rememberUpdatedState(onDrop)

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
                    sectionId = entry.sectionId,
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
                        editController.updateDrag(finger.y, currentSectionOrder)
                    },
                    onDragEnd = {
                        // The one commit of the drag: the move is written
                        // once, when the finger is released.
                        val target = editController.endDrag()
                        editController.exitEditMode()
                        if (target != null) currentDrop(target)
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
    }
}
