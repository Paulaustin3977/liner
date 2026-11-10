package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.HomeEntry

/**
 * The editor for one home item: rename it, move it to another section, or
 * remove it from the home screen.
 *
 * [sections] is the document's sections as id/title pairs; the item can be
 * moved to any of them.
 */
@Composable
fun ItemEditorSheet(
    entry: HomeEntry,
    sections: List<Pair<String, String?>>,
    onRename: (String?) -> Unit,
    onMoveToSection: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(entry.item.id) { mutableStateOf(entry.item.label ?: entry.displayLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.displayLabel) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sections.size > 1) {
                    Text(
                        text = "Move to section",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    sections.forEach { (id, title) ->
                        TextButton(onClick = { onMoveToSection(id) }) {
                            Text(title ?: "Untitled")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(label); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onRemove(); onDismiss() }) { Text("Remove") }
        },
    )
}

/**
 * The editor for one section: rename it, or delete it — and everything in it.
 */
@Composable
fun SectionEditorSheet(
    title: String?,
    onRename: (String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(title ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Section") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(value); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
        },
    )
}
