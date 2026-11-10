package dev.liner.launcher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.liner.launcher.model.LayoutDocument

/** The Sections tab. Fully implemented in Task 13. */
@Composable
fun SectionsTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    Text("Sections")
}
