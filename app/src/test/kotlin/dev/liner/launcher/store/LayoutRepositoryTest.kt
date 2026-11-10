package dev.liner.launcher.store

import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.addSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRepositoryTest {

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
    fun `a readable payload does not report a failure`() = runTest {
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1"))))
        val repository = LayoutRepository(InMemoryDocumentStore(stored))
        repository.load()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a failed read is reported rather than treated as an empty store`() = runTest {
        val store = object : DocumentStore {
            override suspend fun read(): String? = throw IllegalStateException("disk error")
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
        assertTrue(repository.loadFailed.value)
    }

    @Test
    fun `an empty store does not report a failure`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore(null))
        repository.load()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a later successful load clears an earlier failure`() = runTest {
        val store = InMemoryDocumentStore("{not json")
        val repository = LayoutRepository(store)
        repository.load()
        assertTrue(repository.loadFailed.value)
        assertEquals("{not json", repository.recoveredPayload)

        store.write(DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))))
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
}
