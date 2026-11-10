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
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addSection
import dev.liner.launcher.model.moveSection
import dev.liner.launcher.model.renameSection
import dev.liner.launcher.model.removeSection
import dev.liner.launcher.ui.common.SectionEditorSheet
import java.util.UUID

/**
 * The sections tab: create a section, reorder the sections, and open one to
 * rename it or delete it.
 *
 * The title of a section is optional — a section without one shows as
 * "Untitled" on the home screen.
 */
@Composable
fun SectionsTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }
    var editingSectionId by remember { mutableStateOf<String?>(null) }

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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    onClick = { onUpdate { doc -> doc.moveSection(section.id, index - 1) } },
                    enabled = index > 0,
                ) { Text("Up") }
                TextButton(
                    onClick = { onUpdate { doc -> doc.moveSection(section.id, index + 1) } },
                    enabled = index < document.sections.lastIndex,
                ) { Text("Down") }
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
}
