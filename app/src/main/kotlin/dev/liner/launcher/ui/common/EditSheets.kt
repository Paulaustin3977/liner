package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * How tall a dialog's content may grow before it starts scrolling.
 *
 * A dialog does not scroll its content: a list that is taller than the
 * dialog is cut off, and the lower entries can never be reached. The cap
 * gives the content a bounded height, and the scroll lets the user move
 * through it.
 */
private val DIALOG_CONTENT_MAX_HEIGHT = 320.dp

/**
 * The editor for one home item: rename it, move it to another section, or
 * remove it from the home screen.
 *
 * [sections] is the document's sections as id/title pairs; the item can be
 * moved to any of them except the one it is already in.
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
    // The field is seeded with the app's own name when the item has no
    // custom label, so the seeded value has to be remembered to tell a
    // deliberate rename from an untouched field.
    val seededLabel = entry.item.label ?: entry.displayLabel
    var label by remember(entry.item.id) { mutableStateOf(seededLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.displayLabel) },
        text = {
            // The content scrolls: the list of sections can be taller than
            // the dialog, and the lower sections would be unreachable.
            Column(
                Modifier
                    .heightIn(max = DIALOG_CONTENT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The item's own section is not offered: moving an item
                // to the section it is already in would silently push it to
                // the end of that section.
                val targets = moveSectionTargets(sections, entry.sectionId)
                if (targets.isNotEmpty()) {
                    Text(
                        text = "Move to section",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    targets.forEach { (id, title) ->
                        TextButton(onClick = { onMoveToSection(id); onDismiss() }) {
                            Text(title ?: "Untitled")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(resolveEditedLabel(seededLabel, label, entry.item.label))
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onRemove(); onDismiss() }) { Text("Remove") }
        },
    )
}

/**
 * The label an edited item should end up with.
 *
 * [seededLabel] is what the field was seeded with — the app's own name when
 * the item has no custom label — [editedLabel] is what the user left in the
 * field, and [existingLabel] is the item's current custom label.
 *
 * An untouched field must not pin the app's name as an explicit custom
 * label: the row would stop following a later system rename of the app.
 */
internal fun resolveEditedLabel(
    seededLabel: String,
    editedLabel: String,
    existingLabel: String?,
): String? {
    val trimmed = editedLabel.trim()
    return when {
        // An emptied field resets the item to the app's name.
        trimmed.isEmpty() -> null
        // A deliberate rename wins.
        trimmed != seededLabel -> trimmed
        // An untouched field leaves the item's label as it was.
        else -> existingLabel
    }
}

/**
 * The sections an item can be moved to.
 *
 * The item's own section is left out: moving an item to the section it is
 * already in would silently push it to the end of that section.
 */
internal fun moveSectionTargets(
    sections: List<Pair<String, String?>>,
    currentSectionId: String,
): List<Pair<String, String?>> = sections.filter { it.first != currentSectionId }

/**
 * The chooser for the section an item is moved to.
 *
 * The item's own section is left out, for the same reason the item editor
 * leaves it out: moving an item to the section it is already in would
 * silently push it to the end of that section.
 */
@Composable
fun SectionChooserSheet(
    sections: List<Pair<String, String?>>,
    currentSectionId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = moveSectionTargets(sections, currentSectionId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to section") },
        text = {
            // The content scrolls: with more sections than fit on the
            // screen the lower ones would be unreachable — and this chooser
            // is the only way to move an item between sections.
            Column(
                Modifier
                    .heightIn(max = DIALOG_CONTENT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (targets.isEmpty()) {
                    Text("There is no other section to move it to.")
                } else {
                    targets.forEach { (id, title) ->
                        TextButton(onClick = { onSelect(id); onDismiss() }) {
                            Text(title ?: "Untitled")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The editor for one section: rename it, or delete it — and everything in it.
 */
@Composable
fun SectionEditorSheet(
    title: String?,
    itemCount: Int,
    onRename: (String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(title ?: "") }
    // Deleting a section deletes every item in it, and there is no undo for
    // it, so the action asks first.
    var confirmingDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Section") },
        text = {
            if (confirmingDelete) {
                Text(sectionDeletePrompt(itemCount))
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (confirmingDelete) {
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
            } else {
                TextButton(onClick = { onRename(value); onDismiss() }) { Text("Save") }
            }
        },
        dismissButton = {
            if (confirmingDelete) {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            } else {
                TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
            }
        },
    )
}

/** The question asked before a section and every item in it are deleted. */
internal fun sectionDeletePrompt(itemCount: Int): String {
    val noun = if (itemCount == 1) "item" else "items"
    return "Delete this section and its $itemCount $noun?"
}
