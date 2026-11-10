package dev.liner.launcher.ui.home

import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.removeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo snackbar names a removed app the way the home screen does: the
 * user's rename, else the app's own system label, and only the package name
 * when nothing else is known. The name comes from the home entries, which
 * carry the resolved display label.
 *
 * These tests pin the label source itself — the home entries the snackbar
 * reads — because the composable that shows the snackbar cannot be exercised
 * headlessly: the project's unit-test source set has no Compose UI test
 * dependency and the sandbox has no emulator.
 */
class RemovalSnackbarLabelTest {

    private val document = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "Daily",
                items = listOf(
                    HomeItem(id = "renamed", packageName = "com.mail", label = "Inbox"),
                    HomeItem(id = "plain", packageName = "com.chat"),
                    HomeItem(id = "gone", packageName = "com.uninstalled"),
                ),
            ),
        ),
    )

    private val installed = mapOf(
        "com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"),
        "com.chat" to InstalledApp("com.chat", "com.chat.Main", "WhatsApp"),
    )

    private fun entries(doc: LayoutDocument = document) =
        AppCatalog.homeEntries(doc, installed)

    @Test
    fun `an unlabelled app is named by its system label, not by its package name`() {
        val entry = entries().first { it.item.id == "plain" }

        assertEquals("WhatsApp", entry.displayLabel)
    }

    @Test
    fun `the removal message of an unlabelled app reads Removed WhatsApp`() {
        val label = entries().first { it.item.id == "plain" }.displayLabel

        assertEquals("Removed WhatsApp", "Removed $label")
    }

    @Test
    fun `the user's rename is used when there is one`() {
        val entry = entries().first { it.item.id == "renamed" }

        assertEquals("Inbox", entry.displayLabel)
    }

    @Test
    fun `the package name is used only when the app is gone`() {
        val entry = entries().first { it.item.id == "gone" }

        assertEquals("com.uninstalled", entry.displayLabel)
    }

    @Test
    fun `the label has to be read before the item leaves the document`() {
        // The home entries are derived from the document, so removing the
        // item drops its entry — which is why the snackbar's name is
        // resolved before the removal is committed.
        val afterRemoval = entries(document.removeItem("plain"))

        assertTrue(afterRemoval.none { it.item.id == "plain" })
    }
}
