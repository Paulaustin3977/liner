package dev.liner.launcher.store

import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.LayoutDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The single writer for the layout document.
 *
 * Every mutation goes through [update], which updates the in-memory state
 * immediately and persists it asynchronously. Writes are funnelled through one
 * conflated channel consumed by a single coroutine, so two rapid edits can
 * never land out of order — and a burst of edits (a dragged slider fires one
 * per frame) collapses into a single write of the settled state.
 */
class LayoutRepository(private val store: DocumentStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class PendingWrite(
        val document: LayoutDocument,
        val done: CompletableDeferred<Unit>,
    )

    // Conflated: the store only ever needs the newest document, so an
    // update that arrives while another is pending replaces it.
    private val writes = Channel<PendingWrite>(Channel.CONFLATED)
    private val latestWrite = AtomicReference<CompletableDeferred<Unit>?>(null)

    private val _document = MutableStateFlow(LayoutDocument())
    val document: StateFlow<LayoutDocument> = _document.asStateFlow()

    private val _writeFailed = MutableStateFlow(false)
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)

    private val _documentLoaded = MutableStateFlow(false)

    /**
     * True once the store has been read into memory.
     *
     * The signal belongs to the repository, not to a caller of [load]: the
     * store is read once per process, so a second caller's [load] returns
     * immediately — a caller that waited on its own call would proceed
     * before the document was actually in memory, and a write it triggered
     * would persist the default document over the user's stored layout.
     */
    val documentLoaded: StateFlow<Boolean> = _documentLoaded.asStateFlow()

    /** Whether the store has already been read into memory. */
    private val loaded = AtomicBoolean(false)

    /** True when the stored layout could not be read and the defaults were used instead. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** The unreadable payload that [load] fell back from, kept for logging. */
    var recoveredPayload: String? = null
        private set

    init {
        scope.launch {
            for (pending in writes) {
                // Let a burst of updates settle before writing. Dragging a
                // settings slider fires an update per frame, and each write
                // rewrites the whole document — so writing every one of them
                // would hammer the disk for no benefit. The store only ever
                // needs the settled state of the gesture.
                delay(WRITE_QUIET_MS)

                // A newer update may have arrived while we waited. The store
                // only ever needs the newest state, so the older one is
                // superseded rather than written out.
                val newest = writes.tryReceive().getOrNull() ?: pending
                writeDocument(newest)
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
        // The store is read once per process. A second load would race the
        // writer: an edit that has been applied in memory but not yet
        // written to the store would be discarded by the older stored value.
        // Later screens therefore get the document the repository already
        // holds.
        //
        // The trade-off: a store that cannot be read at start-up stays
        // unreadable for the life of the process. The failure is reported
        // through [loadFailed] and the user can retry by restarting the app.
        if (!loaded.compareAndSet(false, true)) return
        readFromStore()
    }

    /**
     * Test hook: re-reads the store, ignoring the once-per-process guard.
     */
    internal suspend fun reload() {
        readFromStore()
    }

    private suspend fun readFromStore() {
        try {
            var readFailed = false
            val raw = try {
                store.read()
            } catch (_: Throwable) {
                // The read itself failed — that is not the same as "nothing
                // stored", and the user's layout must not be reported as if it
                // had never existed.
                readFailed = true
                null
            }

            if (raw == null) {
                // Nothing stored is not a failure, so the flag is cleared here
                // rather than left over from an earlier failed load.
                recoveredPayload = null
                _loadFailed.value = readFailed
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
                recoveredPayload = null
                _loadFailed.value = false
                _document.value = decoded
            }
        } finally {
            // Whether the read succeeded or failed, the document has been
            // dealt with: a failed read leaves the defaults in memory, and
            // the caller must know that too.
            _documentLoaded.value = true
        }
    }

    /**
     * Persists one document and reports whether it worked.
     *
     * A superseded write is never persisted at all — its [PendingWrite.done]
     * is simply dropped, so nothing may await a *specific* write's deferred.
     * [awaitPendingWrite] awaits the newest write, which is always the one
     * that is (or will be) written.
     */
    private suspend fun writeDocument(pending: PendingWrite) {
        try {
            store.write(DocumentCodec.encode(pending.document))
            _writeFailed.value = false
        } catch (e: CancellationException) {
            // Never swallow cancellation — it must propagate so the
            // writer shuts down with the rest of its scope.
            throw e
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

    /**
     * Applies [transform] to the in-memory document and persists the result.
     *
     * A transform that changed nothing queues no write: the transform is
     * expected to return the document it was given when there is nothing to
     * change — the identity reference is what says so, so a transform that
     * rebuilds an equal document is still written. That keeps a no-op edit
     * (a prune that finds nothing stale, say) from rewriting the store.
     */
    fun update(transform: (LayoutDocument) -> LayoutDocument) {
        val current = _document.value
        val next = transform(current)
        // A transform that changed nothing must not queue a write: the store
        // would be rewritten for no reason — once per launcher resume, for a
        // no-op prune, for instance.
        if (next === current) return
        _document.value = next

        val done = CompletableDeferred<Unit>()
        latestWrite.set(done)
        writes.trySend(PendingWrite(next, done))
    }

    /** Test hook: waits for the most recent write to settle. */
    internal suspend fun awaitPendingWrite() {
        latestWrite.get()?.await()
    }

    companion object {
        /** How long the writer waits for a burst of updates to settle. */
        private const val WRITE_QUIET_MS = 100L
    }
}
