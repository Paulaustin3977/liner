package dev.liner.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addSection
import dev.liner.launcher.model.moveItem
import dev.liner.launcher.model.moveSection
import dev.liner.launcher.model.renameSection
import dev.liner.launcher.model.removeSection
import dev.liner.launcher.model.sectionById
import dev.liner.launcher.ui.common.SectionChooserSheet
import dev.liner.launcher.ui.common.SectionEditorSheet
import java.util.UUID

/**
 * The sections tab: create a section, reorder the sections, open one to
 * rename it or delete it, and move the apps between the sections.
 *
 * The title of a section is optional — a section without one shows as
 * "Untitled" on the home screen.
 */
@Composable
fun SectionsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }
    var editingSectionId by remember { mutableStateOf<String?>(null) }
    // The home item the section chooser is open for.
    var movingItemId by remember { mutableStateOf<String?>(null) }
    // The sections whose items are folded away. A section that is not in the
    // set shows its items.
    var collapsedSectionIds by remember { mutableStateOf(emptySet<String>()) }

    // The name of an app, for the items of a section.
    val labels = remember(installedApps) {
        installedApps.associate { it.packageName to it.systemLabel }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            singleLine = true,
            label = { Text("New section title (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                onUpdate { doc -> doc.addSection(UUID.randomUUID().toString(), newTitle) }
                newTitle = ""
            },
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text("Add section")
        }

        Text(
            text = "Tap a section to rename it or delete it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        document.sections.forEachIndexed { index, section ->
            val expanded = section.id !in collapsedSectionIds
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { editingSectionId = section.id },
                    ) {
                        Text(section.title ?: "Untitled")
                        Text(
                            text = "${section.items.size} apps",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            collapsedSectionIds = if (expanded) {
                                collapsedSectionIds + section.id
                            } else {
                                collapsedSectionIds - section.id
                            }
                        },
                    ) { Text(if (expanded) "Collapse" else "Expand") }
                    TextButton(
                        onClick = { onUpdate { doc -> doc.moveSection(section.id, index - 1) } },
                        enabled = index > 0,
                    ) { Text("Up") }
                    TextButton(
                        onClick = { onUpdate { doc -> doc.moveSection(section.id, index + 1) } },
                        enabled = index < document.sections.lastIndex,
                    ) { Text("Down") }
                }

                // The apps of the section, each with the control that moves
                // it to another section.
                if (expanded) {
                    if (section.items.isEmpty()) {
                        Text(
                            text = "No apps",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        )
                    } else {
                        section.items.forEach { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.label
                                        ?: labels[item.packageName]
                                        ?: item.packageName,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { movingItemId = item.id }) {
                                    Text("Move to")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // The editor renames the section and deletes it — and every item in it
    // — after asking first. It is the same editor the home screen uses.
    val editing = editingSectionId?.let { id -> document.sections.firstOrNull { it.id == id } }
    if (editing != null) {
        SectionEditorSheet(
            title = editing.title,
            itemCount = editing.items.size,
            onRename = { title -> onUpdate { doc -> doc.renameSection(editing.id, title) } },
            onDelete = { onUpdate { doc -> doc.removeSection(editing.id) } },
            onDismiss = { editingSectionId = null },
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
