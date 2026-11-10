package dev.liner.launcher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.liner.launcher.model.LayoutDocument

/** The Advanced tab. Fully implemented in Task 14. */
@Composable
fun AdvancedTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    Text("Advanced")
}
