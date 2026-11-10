package dev.liner.launcher.ui.settings

import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The name of an exported layout.
 *
 * The name carries the moment of the export, so two exports do not
 * overwrite each other, and it ends in `.json`, because the file holds the
 * layout document as JSON.
 */
class ImportExportTabTest {

    @Test
    fun `an exported layout is named after the moment it was written`() {
        val name = exportFileName(LocalDateTime.of(2026, 11, 10, 19, 30, 45))

        assertEquals("liner-layout-20261110-193045.json", name)
    }

    @Test
    fun `an exported layout is a json file`() {
        assertTrue(exportFileName(LocalDateTime.of(2026, 1, 2, 3, 4, 5)).endsWith(".json"))
    }

    @Test
    fun `the name of an export is padded`() {
        // The hours, the minutes and the seconds are two digits each, so
        // the name sorts the same way whatever the time is.
        val name = exportFileName(LocalDateTime.of(2026, 1, 2, 3, 4, 5))

        assertEquals("liner-layout-20260102-030405.json", name)
    }

    @Test
    fun `an imported document is stored at the current version`() {
        // A v0 payload stored as a v0 document would skip every future
        // migration, so the import is stored at the version this build
        // understands.
        val imported = importedDocument(LayoutDocument(version = 0))

        assertEquals(LayoutDocument.CURRENT_VERSION, imported.version)
    }

    @Test
    fun `an imported document keeps everything but its version`() {
        val document = LayoutDocument(
            version = 0,
            sections = listOf(Section(id = "s1", title = "Mine")),
        )

        val imported = importedDocument(document)

        assertEquals(document.sections, imported.sections)
        assertEquals(document.theme, imported.theme)
    }
}
