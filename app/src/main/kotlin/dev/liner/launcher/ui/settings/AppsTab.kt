package dev.liner.launcher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument

/** The Apps tab. Fully implemented in Task 13. */
@Composable
fun AppsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    Text("Apps")
}
