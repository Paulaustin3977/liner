package dev.liner.launcher.ui.drawer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app drawer: a sheet over the home screen with a filter field, the apps
 * it matches, and the drawer's own settings entry at the bottom.
 *
 * [style] is the drawer's mode: "text" lists the apps as labels, anything
 * else draws them as icon tiles. [iconCache] loads the tiles' icons — a tile
 * whose icon cannot be loaded shows its label alone.
 */
@Composable
fun DrawerSheet(
    visibleApps: List<InstalledApp>,
    query: String,
    style: String,
    iconCache: IconCache,
    onQueryChange: (String) -> Unit,
    onLaunch: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // The drawer covers the home screen, so it needs a surface of its
            // own: the home labels would otherwise show through its own.
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // The activity draws edge to edge, so the sheet has to keep
                // clear of the status and navigation bars itself.
                .safeDrawingPadding()
                .padding(16.dp)
                // The sheet swallows the taps that land on it, so that only a
                // tap outside it dismisses the drawer.
                .clickable(enabled = false) {},
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Filter apps") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (visibleApps.isEmpty()) {
                Box(
                    Modifier
                        // The list gives up the space the settings row needs,
                        // so the row at the bottom stays on screen.
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No apps match", fontSize = 16.sp)
                }
            } else if (style == "text") {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                ) {
                    items(visibleApps, key = { it.packageName + it.activityClass }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLaunch(app) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(app.systemLabel, fontSize = 18.sp)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f),
                ) {
                    items(visibleApps, key = { it.packageName + it.activityClass }) { app ->
                        Column(
                            Modifier
                                .padding(6.dp)
                                .clickable { onLaunch(app) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Rasterising an icon touches the package manager
                            // and allocates a bitmap, so it must not happen on
                            // the main thread — the drawer can hold a hundred
                            // tiles. The load is keyed by package name, so a
                            // scroll cancels it.
                            val icon by produceState<ImageBitmap?>(
                                initialValue = null,
                                key1 = app.packageName,
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    iconCache.icon(app.packageName)
                                }
                            }
                            // A tile whose icon failed to load keeps its
                            // label alone. The delegated value cannot be
                            // smart-cast, so it is unwrapped here.
                            icon?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                            Text(
                                text = app.systemLabel,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                    )
                }
            }
        }
    }
}
