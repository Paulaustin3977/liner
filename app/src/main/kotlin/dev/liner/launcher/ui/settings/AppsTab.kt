package dev.liner.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addItem
import dev.liner.launcher.model.addSection
import dev.liner.launcher.model.removeItem
import dev.liner.launcher.model.setHiddenFromDrawer
import java.util.UUID

/**
 * The apps tab: every installed app with a checkbox for the home screen.
 *
 * A checked app is on the home screen; an unchecked one is not. The button
 * beside the name hides the app from the drawer (and shows it again). Every
 * change goes through [onUpdate], which is the only thing that writes.
 */
@Composable
fun AppsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val homeItems = remember(document) { document.sections.flatMap { it.items } }
    val homePackages = remember(homeItems) { homeItems.associateBy { it.packageName } }

    val visible = remember(installedApps, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { it.systemLabel.contains(trimmed, ignoreCase = true) }
        }
    }

    // The checkbox and the name do the same thing, so the decision is made
    // once — and it is made against the document as it is NOW: an edit that
    // has not been recomposed yet must not be undone by a stale copy.
    fun toggleHome(packageName: String) {
        onUpdate { doc ->
            val onHome = doc.sections.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { it.packageName == packageName }
            if (onHome != null) {
                doc.removeItem(onHome.id)
            } else {
                // The home screen needs a section to hold the app, so one is
                // created on demand when the document has none.
                val sectionId = doc.sections.firstOrNull()?.id ?: "default-${UUID.randomUUID()}"
                val withSection = if (doc.sections.isEmpty()) doc.addSection(sectionId, null) else doc
                withSection.addItem(
                    item = HomeItem(
                        id = UUID.randomUUID().toString(),
                        packageName = packageName,
                    ),
                    sectionId = sectionId,
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Checked apps appear on your home screen",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.packageName }) { app ->
                val isOnHome = homePackages.containsKey(app.packageName)
                val isHidden = app.packageName in document.hiddenFromDrawer

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = isOnHome,
                        onCheckedChange = { toggleHome(app.packageName) },
                    )
                    Text(
                        text = app.systemLabel,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { toggleHome(app.packageName) },
                    )
                    TextButton(
                        onClick = {
                            onUpdate { doc ->
                                doc.setHiddenFromDrawer(
                                    app.packageName,
                                    app.packageName !in doc.hiddenFromDrawer,
                                )
                            }
                        },
                    ) {
                        Text(if (isHidden) "Show" else "Hide")
                    }
                }
            }
        }

        Text(
            text = "Home order: ${homeItems.size} apps",
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
