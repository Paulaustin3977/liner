package dev.liner.launcher.store

import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.LayoutDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The single writer for the layout document.
 *
 * Every mutation goes through [update], which updates the in-memory state
 * immediately and persists it asynchronously. Writes are funnelled through one
 * unbounded channel consumed by a single coroutine, so two rapid edits can
 * never land out of order.
 */
class LayoutRepository(private val store: DocumentStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class PendingWrite(
        val document: LayoutDocument,
        val done: CompletableDeferred<Unit>,
    )

    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)
    private val latestWrite = AtomicReference<CompletableDeferred<Unit>?>(null)

    private val _document = MutableStateFlow(LayoutDocument())
    val document: StateFlow<LayoutDocument> = _document.asStateFlow()

    private val _writeFailed = MutableStateFlow(false)
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    init {
        scope.launch {
            for (pending in writes) {
                try {
                    store.write(DocumentCodec.encode(pending.document))
                    _writeFailed.value = false
                } catch (_: Throwable) {
                    _writeFailed.value = true
                } finally {
                    pending.done.complete(Unit)
                }
            }
        }
    }

    /** Reads the stored document. Falls back to defaults when it cannot be read. */
    suspend fun load() {
        val raw = try {
            store.read()
        } catch (_: Throwable) {
            null
        }
        _document.value = try {
            raw?.let { DocumentCodec.decode(it) } ?: LayoutDocument()
        } catch (_: Exception) {
            LayoutDocument()
        }
    }

    fun update(transform: (LayoutDocument) -> LayoutDocument) {
        val next = transform(_document.value)
        _document.value = next

        val done = CompletableDeferred<Unit>()
        latestWrite.set(done)
        writes.trySend(PendingWrite(next, done))
    }

    /** Test hook: waits for the most recent write to settle. */
    internal suspend fun awaitPendingWrite() {
        latestWrite.get()?.await()
    }
}
