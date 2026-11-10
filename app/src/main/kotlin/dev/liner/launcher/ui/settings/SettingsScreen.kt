package dev.liner.launcher.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.updateTheme

private val TABS = listOf("Appearance", "Apps", "Sections", "Import / Export", "Advanced")
private val TAB_KEYS = listOf("appearance", "apps", "sections", "importexport", "advanced")

/**
 * The settings screen: the tabs, and the tab the user was sent to.
 *
 * The screen holds no state of its own beyond the selected tab — every
 * change goes back to the caller, which is the only thing that writes to
 * the store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    initialTab: String?,
    writeFailed: Boolean,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val startIndex = remember(initialTab) { tabIndexFor(initialTab) }
    var selectedTab by rememberSaveable { mutableIntStateOf(startIndex) }

    // A failed save must not be silent.
    val context = LocalContext.current
    LaunchedEffect(writeFailed) {
        if (writeFailed) {
            Toast.makeText(
                context,
                "Your changes could not be saved. Try again.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Liner Settings") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                when (TABS[selectedTab]) {
                    "Appearance" -> AppearanceTab(
                        theme = document.theme,
                        onThemeChange = { transform -> onUpdate { it.updateTheme(transform) } },
                    )

                    "Apps" -> AppsTab(
                        document = document,
                        installedApps = installedApps,
                        onUpdate = onUpdate,
                    )

                    "Sections" -> SectionsTab(
                        document = document,
                        onUpdate = onUpdate,
                    )

                    "Import / Export" -> ImportExportTab(
                        document = document,
                        onUpdate = onUpdate,
                    )

                    "Advanced" -> AdvancedTab(
                        document = document,
                        onUpdate = onUpdate,
                    )
                }
            }
        }
    }
}

/**
 * The index of the tab the user was sent to.
 *
 * The keys are the ones the rest of the app opens settings with — the
 * drawer's gear opens "appearance", the home screen's empty state opens
 * "apps". An unknown or missing key falls back to the first tab rather
 * than leaving the screen on nothing.
 */
internal fun tabIndexFor(key: String?): Int {
    val index = key?.let { TAB_KEYS.indexOf(it) } ?: -1
    return index.coerceAtLeast(0)
}
