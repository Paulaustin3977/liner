package dev.liner.launcher.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.ImportValidator
import dev.liner.launcher.model.LayoutDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Where an exported layout is written before it is shared. */
private const val EXPORT_DIRECTORY = "exports"

/** The timestamp in the name of an exported layout. */
private val EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * The types the file picker offers when a layout is imported.
 *
 * A `.json` file is not reported as JSON by every file provider, so the
 * plain-text and octet-stream types are offered too — otherwise the file
 * the user exported could be invisible to the picker.
 */
private val IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream",
)

/**
 * How much of an imported file is read.
 *
 * A layout document is a few kilobytes. A megabyte is far more than any of
 * them needs, and the cap keeps a file that is not a layout from filling
 * the launcher's memory.
 */
private const val MAX_IMPORT_BYTES = 1 shl 20

/**
 * The Import / Export tab: a backup of the layout, and the way back into it.
 *
 * Exporting writes the layout document to a file — the document itself, at
 * the top level, so the file can be edited by hand — and offers it through
 * the share sheet; it can also be copied to the clipboard.
 *
 * Importing reads a layout from a file or from the clipboard, checks it, and
 * shows what it would change before it replaces the current layout. A
 * payload that cannot be read — or that comes from a newer version of
 * Liner — is explained rather than applied.
 */
@Composable
fun ImportExportTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The import that has been read and checked, but not yet committed.
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    // A failure the user has to be told about.
    var failure by remember { mutableStateOf<Failure?>(null) }

    // Checks a payload and offers it to the user. The summary is what the
    // user sees before their layout is replaced.
    fun offer(raw: String) {
        val result = try {
            ImportValidator.validate(raw, document)
        } catch (_: Error) {
            // The validator lets Errors through on purpose: a bug inside the
            // decoder must not be shown to the user as a broken file. It must
            // still not take the launcher down, so the failure is reported
            // as the unexpected failure it is.
            null
        }
        val imported = result?.document
        if (imported == null) {
            failure = Failure(
                "Import failed",
                result?.error ?: "The import could not be completed.",
            )
        } else {
            pending = PendingImport(imported, result.summary)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        // A cancelled picker gives no file, and that is not a failure.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) { readText(context, uri) }
            if (raw == null) {
                failure = Failure("Import failed", "That file could not be read.")
            } else {
                offer(raw)
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Export", modifier = Modifier.padding(top = 8.dp))
        TextButton(
            onClick = {
                scope.launch {
                    if (!shareLayoutAsFile(context, document)) {
                        failure = Failure("Export failed", "The layout could not be exported.")
                    }
                }
            },
        ) { Text("Share layout as a file") }

        TextButton(onClick = { copyToClipboard(context, document) }) {
            Text("Copy layout to clipboard")
        }

        Text("Import", modifier = Modifier.padding(top = 24.dp))
        TextButton(onClick = { filePicker.launch(IMPORT_MIME_TYPES) }) {
            Text("Import from a file")
        }
        TextButton(
            onClick = {
                val raw = readClipboard(context)
                if (raw == null) {
                    failure = Failure("Import failed", "There is nothing on the clipboard to import.")
                } else {
                    offer(raw)
                }
            },
        ) { Text("Import from clipboard") }

        Text(
            text = "Importing replaces your current layout. Export first if you want a backup.",
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    // What the import would change, before it changes it.
    val import = pending
    if (import != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import this layout?") },
            text = {
                Column {
                    import.summary.forEach { change -> Text(change) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdate { importedDocument(import.document) }
                        pending = null
                    },
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }

    val problem = failure
    if (problem != null) {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(problem.title) },
            text = { Text(problem.message) },
            confirmButton = {
                TextButton(onClick = { failure = null }) { Text("Close") }
            },
        )
    }
}

/** An import that has been read and checked, but not yet committed. */
private data class PendingImport(
    val document: LayoutDocument,
    val summary: List<String>,
)

/**
 * The document an import stores.
 *
 * The payload keeps the version it was written with, and a v0 document
 * stored as a v0 document would skip every future migration. It is stored
 * at the version this build understands.
 */
internal fun importedDocument(document: LayoutDocument): LayoutDocument =
    document.copy(version = LayoutDocument.CURRENT_VERSION)

/** A failure the user has to be told about. */
private data class Failure(
    val title: String,
    val message: String,
)

/**
 * The name of an exported layout.
 *
 * The moment of the export is part of the name, so two exports do not
 * overwrite each other.
 */
internal fun exportFileName(timestamp: LocalDateTime): String =
    "liner-layout-${timestamp.format(EXPORT_TIMESTAMP)}.json"

/**
 * Writes the layout to a file and offers it through the share sheet.
 *
 * The document is written as it is — at the top level of the file, with no
 * wrapper around it — so the file stays editable by hand.
 *
 * The file is written off the main thread and the share sheet is opened on
 * it. A failure is reported rather than thrown: the launcher must not crash
 * because a file could not be written.
 */
private suspend fun shareLayoutAsFile(context: Context, document: LayoutDocument): Boolean {
    val shared = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
            val file = File(directory, exportFileName(LocalDateTime.now()))
            file.writeText(DocumentCodec.encode(document))
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }
    }.getOrNull() ?: return false

    return runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, shared)
            putExtra(Intent.EXTRA_SUBJECT, "Liner layout")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The clip lets the receiver read the file even on the platforms
            // that do not pass the grant flag on by themselves.
            clipData = ClipData.newUri(context.contentResolver, "Liner layout", shared)
        }
        val chooser = Intent.createChooser(intent, "Share layout")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ContextCompat.startActivity(context, chooser, null)
    }.isSuccess
}

/** Copies the layout to the clipboard. */
private fun copyToClipboard(context: Context, document: LayoutDocument) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Liner layout", DocumentCodec.encode(document)))
}

/** The text on the clipboard, or null when there is none. */
private fun readClipboard(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip
    if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * The text of a picked file, or null when it cannot be read.
 *
 * Only the first megabyte is read: a layout document is a few kilobytes,
 * and a file that is not a layout must not fill the launcher's memory.
 */
private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val buffer = ByteArray(MAX_IMPORT_BYTES)
        var read = 0
        while (read < buffer.size) {
            val count = stream.read(buffer, read, buffer.size - read)
            if (count <= 0) break
            read += count
        }
        String(buffer, 0, read, Charsets.UTF_8)
    }
}.getOrNull()
