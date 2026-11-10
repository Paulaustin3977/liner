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

    private val _loadFailed = MutableStateFlow(false)

    /** True when the stored layout could not be read and the defaults were used instead. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** The unreadable payload that [load] fell back from, kept for logging. */
    var recoveredPayload: String? = null
        private set

    init {
        scope.launch {
            for (pending in writes) {
                try {
                    store.write(DocumentCodec.encode(pending.document))
                    _writeFailed.value = false
                } catch (_: Throwable) {
                    // Throwable here, unlike the model layer's Exception: this
                    // is a long-lived writer coroutine, and letting an Error
                    // escape would kill it — after which every later edit would
                    // silently stop being persisted.
                    _writeFailed.value = true
                } finally {
                    pending.done.complete(Unit)
                }
            }
        }
    }

    /**
     * Reads the stored document.
     *
     * This is the recovery boundary at app start, so it catches Throwable
     * rather than Exception: a pathological payload (deeply nested JSON
     * raising StackOverflowError) must not crash the launcher, which the spec
     * explicitly forbids.
     *
     * When the stored payload cannot be decoded the document falls back to
     * defaults, but the failure is *reported* rather than silent: [loadFailed]
     * goes true and the original payload is kept in [recoveredPayload] so the
     * platform layer can log it and the UI can tell the user their layout was
     * reset instead of quietly losing it.
     */
    suspend fun load() {
        val raw = try {
            store.read()
        } catch (_: Throwable) {
            null
        }

        if (raw == null) {
            _document.value = LayoutDocument()
            return
        }

        val decoded = try {
            DocumentCodec.decode(raw)
        } catch (_: Throwable) {
            null
        }

        if (decoded == null) {
            recoveredPayload = raw
            _loadFailed.value = true
            _document.value = LayoutDocument()
        } else {
            _document.value = decoded
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
