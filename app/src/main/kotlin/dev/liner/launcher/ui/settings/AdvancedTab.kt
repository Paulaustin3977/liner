package dev.liner.launcher.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.model.setAutoPruneUninstalled
import dev.liner.launcher.platform.DefaultHomeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Advanced tab: the launcher's behaviour that is not about the look of
 * the home screen.
 *
 * Every change goes through [onUpdate], like every other settings tab — the
 * tab itself writes nothing.
 */
@Composable
fun AdvancedTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val context = LocalContext.current
    val isDefaultHome = rememberDefaultHome(context)
    var confirmingReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // While the answer is still unknown the banner is not shown: it must
        // not flash in and out as the answer arrives.
        if (isDefaultHome == false) {
            Text(
                text = "Liner is not your default launcher.",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Your home screen shows only while Liner is open.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { DefaultHomeDetector.requestDefaultHome(context) },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text("Set as default launcher") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Remove uninstalled apps automatically")
                Text(
                    text = if (document.autoPruneUninstalled) {
                        "Uninstalled apps are removed from your home screen automatically."
                    } else {
                        "Uninstalled apps stay on your home screen, greyed out."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = document.autoPruneUninstalled,
                onCheckedChange = { enabled ->
                    onUpdate { it.setAutoPruneUninstalled(enabled) }
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        TextButton(onClick = { confirmingReset = true }) { Text("Reset everything") }
    }

    // The reset throws the layout away, and there is no undo for it — so it
    // asks first.
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset everything?") },
            text = {
                Text(
                    "Your apps, your sections and your theme go back to their defaults. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdate { doc -> resetToDefaults(doc) }
                        confirmingReset = false
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The document after everything has been reset to its defaults.
 *
 * The sections, the theme, the hidden apps and the auto-prune setting all
 * go: a reset is a reset. The version stays — it is the version of the
 * document, not one of its settings.
 */
internal fun resetToDefaults(document: LayoutDocument): LayoutDocument = document.copy(
    sections = emptyList(),
    theme = ThemeTokens(),
    hiddenFromDrawer = emptyList(),
    autoPruneUninstalled = false,
)

/**
 * Whether Liner is the default home app.
 *
 * The answer is null until it is known: it takes a round trip to the role
 * manager or the package manager, so it is asked off the main thread and
 * after the composition. The banner must not flash before the answer
 * arrives.
 *
 * The question is asked once when the tab appears, and again whenever the
 * screen comes back to the foreground: the user leaves for the system's
 * home-app settings and comes back, and the answer may have changed.
 */
@Composable
private fun rememberDefaultHome(context: Context): Boolean? {
    var isDefaultHome by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Asked once when the tab appears.
    LaunchedEffect(context) {
        isDefaultHome = withContext(Dispatchers.IO) {
            DefaultHomeDetector.isDefaultHome(context)
        }
    }

    // …and again whenever the screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    isDefaultHome = withContext(Dispatchers.IO) {
                        DefaultHomeDetector.isDefaultHome(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return isDefaultHome
}
