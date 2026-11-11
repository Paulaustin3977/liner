package dev.liner.launcher.store

import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.addSection
import dev.liner.launcher.model.renameSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayoutRepositoryTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `load reads the stored document`() = runTest {
        val store = InMemoryDocumentStore(DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))))

        val repository = LayoutRepository(store)
        repository.load()

        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
    }

    @Test
    fun `load falls back to defaults when the stored payload is corrupt`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore("{not json"))
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
    }

    @Test
    fun `a corrupt payload is reported rather than silently discarded`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore("{not json"))
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
        assertTrue(repository.loadFailed.value)
        assertEquals("{not json", repository.recoveredPayload)
    }

    @Test
    fun `a failed read is reported rather than treated as nothing stored`() = runTest {
        val store = object : DocumentStore {
            override suspend fun read(): String? = throw IllegalStateException("disk unreadable")
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
        assertTrue(repository.loadFailed.value)
    }

    @Test
    fun `an empty store is not a failure`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore(null))
        repository.load()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a readable payload does not report a failure`() = runTest {
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1"))))
        val repository = LayoutRepository(InMemoryDocumentStore(stored))
        repository.load()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `load falls back to defaults when there is nothing stored`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore(null))
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
    }

    @Test
    fun `update mutates the in memory document and persists it`() = runTest {
        val store = InMemoryDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1", "One") }
        repository.awaitPendingWrite()

        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
        assertTrue(store.read()!!.contains("\"s1\""))
    }

    @Test
    fun `a failed write is flagged and retried on the next update`() = runTest {
        val store = object : DocumentStore {
            var failNext = true
            val written = mutableListOf<String>()
            override suspend fun read(): String? = null
            override suspend fun write(json: String) {
                if (failNext) { failNext = false; throw IllegalStateException("disk full") }
                written += json
            }
        }
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1") }
        repository.awaitPendingWrite()
        assertTrue(repository.writeFailed.value)

        repository.update { it.addSection("s2") }
        repository.awaitPendingWrite()
        assertFalse(repository.writeFailed.value)
        assertEquals(1, store.written.size)
    }

    @Test
    fun `concurrent updates are serialised so the last write wins`() = runTest {
        val store = InMemoryDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1") }
        repository.update { it.addSection("s2") }
        repository.awaitPendingWrite()

        assertEquals(listOf("s1", "s2"), repository.document.value.sections.map { it.id })
        assertTrue(store.read()!!.contains("s2"))
    }

    @Test
    fun `a later successful load clears an earlier failure`() = runTest {
        val store = InMemoryDocumentStore("{not json")
        val repository = LayoutRepository(store)
        repository.load()
        assertTrue(repository.loadFailed.value)
        assertEquals("{not json", repository.recoveredPayload)

        store.write(DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))))
        repository.reload()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a cancelled write is not reported as a write failure`() = runTest {
        val store = object : DocumentStore {
            override suspend fun read(): String? = null
            override suspend fun write(json: String): Unit = throw CancellationException("cancelled")
        }
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1") }
        repository.awaitPendingWrite()

        assertFalse(repository.writeFailed.value)
    }

    @Test
    fun `an empty store clears a failure from an earlier failed read`() = runTest {
        val store = object : DocumentStore {
            var failRead = true
            override suspend fun read(): String? {
                if (failRead) { failRead = false; throw IllegalStateException("disk unreadable") }
                return null
            }
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        repository.load()
        assertTrue(repository.loadFailed.value)

        repository.reload()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a second load does not discard an edit that has not been written back yet`() = runTest {
        // The write is held back, so the store keeps the pre-edit document
        // for as long as the test needs it to.
        val gate = CompletableDeferred<Unit>()
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0"))))
        val store = object : DocumentStore {
            var content: String? = stored
            override suspend fun read(): String? = content
            override suspend fun write(json: String) {
                gate.await()
                content = json
            }
        }
        val repository = LayoutRepository(store)
        repository.load()

        // The edit is applied in memory and queued for the writer, but the
        // write has not landed yet.
        repository.update { it.addSection("s1", "One") }

        // A second read of the store would replace the in-memory document
        // with the pre-edit one and throw the edit away.
        repository.load()

        assertEquals(listOf("s0", "s1"), repository.document.value.sections.map { it.id })

        gate.complete(Unit)
    }

    @Test
    fun `a burst of updates is persisted once, with the final state`() = runTest {
        // A warm-up write on a throwaway repository pays the class-loading
        // cost, so the measured burst below is not slowed down by the first
        // write on a cold JVM. It is a write of its own, so the document the
        // burst is measured on stays untouched.
        val warmUp = LayoutRepository(CountingDocumentStore())
        warmUp.load()
        warmUp.update { it.addSection("warmup") }
        warmUp.awaitPendingWrite()

        val store = CountingDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        // Dragging a settings slider fires an update per frame. The store
        // only ever needs the settled state of the gesture, so the burst has
        // to collapse into a single write of the final document.
        repeat(40) { index ->
            repository.update { it.addSection("s$index") }
        }

        repository.awaitPendingWrite()

        assertEquals(1, store.writeCount)

        // The live preview still saw every movement: the in-memory document
        // holds all of them, and the one write that did land holds them all
        // too.
        val expected = (0 until 40).map { "s$it" }
        assertEquals(expected, repository.document.value.sections.map { it.id })
        val decoded = store.lastWritten?.let(DocumentCodec::decode)
        assertEquals(expected, decoded?.sections?.map { it.id })
    }

    @Test
    fun `a transform that changed nothing does not write`() = runTest {
        // The prune finds nothing stale on most resumes, and the switch is
        // off on most of them: the transform returns the document it was
        // given, and the store must not be rewritten for it.
        val store = CountingDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it }
        repository.awaitPendingWrite()

        assertEquals(0, store.writeCount)
    }

    @Test
    fun `a transform that rebuilds an equal document is still written`() = runTest {
        // The rule is the identity reference, not equality: a transform that
        // rebuilds the document writes it, even when the result is equal.
        val store = CountingDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.copy() }
        repository.awaitPendingWrite()

        assertEquals(1, store.writeCount)
    }

    @Test
    fun `the document is not reported as loaded before the store is read`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore(null))

        assertFalse(repository.documentLoaded.value)

        repository.load()

        assertTrue(repository.documentLoaded.value)
    }

    @Test
    fun `the document is reported as loaded even when the read failed`() = runTest {
        // Whether the read succeeded or failed, the caller has to know that
        // the read is over: a failed read leaves the defaults in memory, and
        // a write that followed would destroy the layout on the disk.
        val store = object : DocumentStore {
            override suspend fun read(): String? = throw IllegalStateException("disk unreadable")
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        repository.load()

        assertTrue(repository.documentLoaded.value)
        assertTrue(repository.loadFailed.value)
    }

    @Test
    fun `a second caller waits for the document that is still being read`() = runTest {
        // The store is read once per process, so a second caller's load()
        // returns immediately — it cannot tell from its own call that the
        // document is in memory yet. The repository's signal can: it stays
        // false until the read has finished.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1"))))
        val store = object : DocumentStore {
            override suspend fun read(): String? {
                entered.complete(Unit)
                gate.await()
                return stored
            }
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        val first = async { repository.load() }
        // The read has started and is waiting for the gate.
        entered.await()
        assertFalse(repository.documentLoaded.value)

        // The second caller gets the document the repository already holds —
        // but the document is not in memory yet.
        repository.load()
        assertFalse(repository.documentLoaded.value)

        gate.complete(Unit)
        first.await()

        assertTrue(repository.documentLoaded.value)
        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
    }

    @Test
    fun `an edit that arrives before the store is read waits for the stored document`() = runTest {
        // The read of the store is held at a gate, so the edit below lands
        // while the document is still being read.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0"))))
        val store = object : DocumentStore {
            override suspend fun read(): String? {
                entered.complete(Unit)
                gate.await()
                return stored
            }
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        val loading = async { repository.load() }
        entered.await()

        // The user unchecks an app in the settings while the store is still
        // being read.
        repository.update { it.addSection("s1", "One") }

        // The edit is held, not applied: the document in memory is still the
        // default one, so the edit was not computed against the wrong base.
        assertEquals(LayoutDocument(), repository.document.value)

        gate.complete(Unit)
        loading.await()
    }

    @Test
    fun `an edit that arrives before the store is read is applied to the stored document`() = runTest {
        // The store holds the user's layout, and the read of it is held at a
        // gate: the edit below lands while the read is still running.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0"))))
        val store = object : DocumentStore {
            var content: String? = stored
            override suspend fun read(): String? {
                entered.complete(Unit)
                gate.await()
                return content
            }
            override suspend fun write(json: String) { content = json }
        }
        val repository = LayoutRepository(store)

        val loading = async { repository.load() }
        entered.await()

        repository.update { it.addSection("s1", "One") }

        gate.complete(Unit)
        loading.await()
        repository.awaitPendingWrite()

        // The edit was applied to the STORED document, not to the defaults.
        assertEquals(listOf("s0", "s1"), repository.document.value.sections.map { it.id })
        // And the stored layout was not overwritten with the defaults.
        val written = DocumentCodec.decode(store.content!!)
        assertEquals(listOf("s0", "s1"), written?.sections?.map { it.id })
    }

    @Test
    fun `several edits that arrive before the store is read are all applied, in order`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0"))))
        val store = object : DocumentStore {
            var content: String? = stored
            override suspend fun read(): String? {
                entered.complete(Unit)
                gate.await()
                return content
            }
            override suspend fun write(json: String) { content = json }
        }
        val repository = LayoutRepository(store)

        val loading = async { repository.load() }
        entered.await()

        // Three edits, made in this order.
        repository.update { it.addSection("s1", "One") }
        repository.update { it.renameSection("s1", "Two") }
        repository.update { it.renameSection("s1", "Three") }

        gate.complete(Unit)
        loading.await()
        repository.awaitPendingWrite()

        // All three landed, on the stored document, in the order they were
        // made: the last rename is the one that shows.
        val sections = repository.document.value.sections
        assertEquals(listOf("s0", "s1"), sections.map { it.id })
        assertEquals("Three", sections.last().title)
        val written = DocumentCodec.decode(store.content!!)
        assertEquals("Three", written?.sections?.last()?.title)
    }

    @Test
    fun `an edit that arrives after the store is read is applied at once`() = runTest {
        val store = InMemoryDocumentStore(
            DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0")))),
        )
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1", "One") }

        // The document is in memory, so the edit lands straight away — there
        // is nothing to wait for.
        assertEquals(listOf("s0", "s1"), repository.document.value.sections.map { it.id })

        repository.awaitPendingWrite()
        val written = DocumentCodec.decode(store.read()!!)
        assertEquals(listOf("s0", "s1"), written?.sections?.map { it.id })
    }

    @Test
    fun `a cancelled read does not replace the layout with the defaults`() = runTest {
        // The store holds the user's layout, and the read of it is held at a
        // gate: the read can be cancelled while it is in flight, the way a
        // screen that goes away cancels the read it started.
        val store = GatedDocumentStore(
            DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))),
        )
        val repository = LayoutRepository(store)

        val loading = launch { repository.load() }
        store.entered.await()
        loading.cancelAndJoin()

        // The read was aborted, not failed: the store says nothing about the
        // layout, so nothing is reported and the document is not touched.
        assertFalse(repository.documentLoaded.value)
        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)

        // The aborted read did not consume the once-per-process flag, so
        // the next caller reads the store again instead of being left with
        // the defaults for the rest of the process.
        repository.load()

        assertTrue(repository.documentLoaded.value)
        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
    }

    @Test
    fun `a cancelled read does not apply a queued edit to the defaults`() = runTest {
        val store = GatedDocumentStore(
            DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0")))),
        )
        val repository = LayoutRepository(store)

        val loading = launch { repository.load() }
        store.entered.await()

        // The user edits the layout while the store is still being read.
        repository.update { it.addSection("s1", "One") }

        loading.cancelAndJoin()

        // The edit was not applied to the default document: the read was
        // aborted, so the document in memory is still the default one and
        // the edit is still queued.
        assertEquals(LayoutDocument(), repository.document.value)
        assertFalse(repository.documentLoaded.value)

        // The next read applies the queued edit to the stored layout, and
        // the stored layout is not overwritten with the defaults.
        repository.load()
        repository.awaitPendingWrite()

        assertEquals(listOf("s0", "s1"), repository.document.value.sections.map { it.id })
        val written = DocumentCodec.decode(store.content!!)
        assertEquals(listOf("s0", "s1"), written?.sections?.map { it.id })
    }

    @Test
    fun `a cancelled re-read leaves the loaded layout in place`() = runTest {
        val store = GatedDocumentStore(
            DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))),
            holdOnRead = 2,
        )
        val repository = LayoutRepository(store)

        repository.load()
        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })

        // A second read of the store is cancelled.
        val reading = launch { repository.reload() }
        store.entered.await()
        reading.cancelAndJoin()

        // The layout that was read is still in memory: a cancelled read says
        // nothing about the store, so it must not replace the document with
        // the defaults, and it must not report a failure.
        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `an unreadable payload is preserved in the recovery log`() = runTest {
        val dir = tempFolder.newFolder("recovery")
        val repository = LayoutRepository(InMemoryDocumentStore("{not json"), recoveryLogDir = dir)

        repository.load()

        val log = File(dir, LayoutRepository.RECOVERY_LOG_NAME)
        assertTrue(log.exists())
        assertEquals("{not json", log.readText())
    }

    @Test
    fun `the recovery log is written once`() = runTest {
        val dir = tempFolder.newFolder("recovery")
        val repository = LayoutRepository(InMemoryDocumentStore("{not json"), recoveryLogDir = dir)

        repository.load()
        val log = File(dir, LayoutRepository.RECOVERY_LOG_NAME)
        assertTrue(log.exists())

        // The same broken payload is read again: the log is not written a
        // second time.
        log.delete()
        repository.reload()

        assertFalse(log.exists())
    }

    @Test
    fun `the recovery log survives the edit that replaces the broken layout`() = runTest {
        // The settings screen can be the first screen of the process: it
        // loads the layout, and the first edit then replaces the broken
        // payload on disk. The copy has to exist before that happens.
        val dir = tempFolder.newFolder("recovery")
        val store = InMemoryDocumentStore("{not json")
        val repository = LayoutRepository(store, recoveryLogDir = dir)

        repository.load()

        repository.update { it.addSection("s1", "One") }
        repository.awaitPendingWrite()

        val log = File(dir, LayoutRepository.RECOVERY_LOG_NAME)
        assertTrue(log.exists())
        assertEquals("{not json", log.readText())
    }
}

/**
 * A [DocumentStore] that counts the writes it receives, so a test can pin
 * how many times the document was persisted.
 */
private class CountingDocumentStore : DocumentStore {
    private val lock = Any()
    private val written = mutableListOf<String>()

    /** How many times the document was written. */
    val writeCount: Int get() = synchronized(lock) { written.size }

    /** The payload of the last write, or null if nothing was written. */
    val lastWritten: String? get() = synchronized(lock) { written.lastOrNull() }

    override suspend fun read(): String? = null

    override suspend fun write(json: String) {
        synchronized(lock) { written += json }
    }
}

/**
 * A [DocumentStore] whose read can be held at a gate, so a test can cancel
 * a read while it is in flight — the way a screen that goes away cancels the
 * read of the layout it started.
 */
private class GatedDocumentStore(
    stored: String?,
    private val holdOnRead: Int = 1,
) : DocumentStore {

    /** Completes when the held read has started. */
    val entered = CompletableDeferred<Unit>()

    /** The held read waits for this. */
    val gate = CompletableDeferred<Unit>()

    /** How many times the store has been read. */
    var reads = 0
        private set

    /** The content of the store. */
    var content: String? = stored

    override suspend fun read(): String? {
        reads++
        if (reads == holdOnRead) {
            entered.complete(Unit)
            gate.await()
        }
        return content
    }

    override suspend fun write(json: String) {
        content = json
    }
}
