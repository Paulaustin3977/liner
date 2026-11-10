package dev.liner.launcher.ui.drawer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.ui.common.BackgroundLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app drawer: a sheet over the home screen with a filter field, the apps
 * it matches, and the drawer's own settings entry at the bottom.
 *
 * [style] is the drawer's mode: "text" lists the apps as labels, anything
 * else draws them as icon tiles. [iconCache] loads the tiles' icons — a tile
 * whose icon cannot be loaded shows its label alone.
 *
 * The sheet draws the home screen's own [background] with a scrim over it, so
 * a themed launcher stays coherent and the list stays legible over a
 * wallpaper or a bright gradient. [appsLoaded] tells the sheet whether the app
 * query has answered yet: an empty list before that means "not loaded", not
 * "no apps match".
 */
@Composable
fun DrawerSheet(
    visibleApps: List<InstalledApp>,
    query: String,
    style: String,
    iconCache: IconCache,
    background: BackgroundSpec,
    wallpaper: Bitmap?,
    appsLoaded: Boolean,
    systemTextColor: Color,
    systemBackgroundColor: Color,
    onQueryChange: (String) -> Unit,
    onLaunch: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The sheet covers the whole screen, so there is no "outside" to tap —
    // Back is what closes it.
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        // The drawer shares the home screen's background so a themed launcher
        // stays coherent, with a scrim over it so the list stays legible on a
        // wallpaper or a bright gradient.
        BackgroundLayer(
            spec = background,
            wallpaper = wallpaper,
            systemTextColor = systemTextColor,
            systemBackgroundColor = systemBackgroundColor,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .safeDrawingPadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Filter apps") },
                modifier = Modifier.fillMaxWidth(),
            )

            // The list takes the space the filter field and the settings row
            // leave; without the weight it would fill the column and push
            // the settings row out of view.
            Box(Modifier.weight(1f)) {
                if (!appsLoaded) {
                    // The app query has not finished yet — an empty list here
                    // means "not loaded", not "no apps match".
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
                } else if (visibleApps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No apps match", fontSize = 16.sp)
                    }
                } else if (style == "text") {
                    LazyColumn {
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
                    // Four columns, adapting down to three on a narrow screen.
                    BoxWithConstraints {
                        val columns = if (maxWidth < 360.dp) 3 else 4
                        LazyVerticalGrid(columns = GridCells.Fixed(columns)) {
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
                                    // A `by` delegate cannot be smart-cast, so the
                                    // null case is handled with `let`.
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
