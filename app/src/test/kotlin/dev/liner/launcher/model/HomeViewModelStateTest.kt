package dev.liner.launcher.model

import dev.liner.launcher.store.InMemoryDocumentStore
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
class HomeViewModelStateTest {

    @Test
    fun `the catalog reflects the document and the installed apps`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "com.mail", label = "Inbox"))),
            ),
        )
        val installed = mapOf("com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"))

        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals(1, entries.size)
        assertEquals("Inbox", entries[0].displayLabel)
    }

    @Test
    fun `a stored document round trips through the repository`() = runTest {
        val store = InMemoryDocumentStore()
        val repository = LayoutRepository(store)

        repository.load()
        repository.update { it.addSection("s1", "Daily") }
        repository.awaitPendingWrite()

        // The repository has just written the document, so the store holds it.
        assertEquals(DocumentCodec.decode(store.read()!!), repository.document.value)
    }
}
