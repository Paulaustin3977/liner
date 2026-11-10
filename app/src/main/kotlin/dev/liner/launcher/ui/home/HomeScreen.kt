package dev.liner.launcher.ui.home

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.ClockFormatter
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.StyleResolver
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.ui.common.BackgroundLayer
import dev.liner.launcher.ui.common.LabelRow
import dev.liner.launcher.ui.common.SectionHeaderRow
import dev.liner.launcher.ui.drawer.DrawerSheet
import dev.liner.launcher.ui.drawer.DrawerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * The home screen: a clock, a date, and the user's apps as a list of labels.
 *
 * The screen renders the [HomeViewModel]'s state and reports every interaction
 * back to the caller — it holds no state of its own beyond the clock.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    wallpaper: Bitmap?,
    iconCache: IconCache,
    onLaunch: (HomeEntry) -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenSettings: (String?) -> Unit,
    onRecoverLayout: (String) -> Unit,
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val entries by viewModel.homeEntries.collectAsStateWithLifecycle()
    val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
    val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
    val appsLoaded by viewModel.appsLoaded.collectAsStateWithLifecycle()
    val documentLoaded by viewModel.documentLoaded.collectAsStateWithLifecycle()

    // The drawer is a sheet over the home screen. It is opened by an upward
    // drag from the bottom of the screen and closed by launching an app, by
    // opening the settings, or with the back button.
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // The drawer view model belongs to the view model store, so its filter
    // query survives a configuration change.
    val drawerViewModel: DrawerViewModel = viewModel(
        factory = DrawerViewModel.factory(viewModel.document, viewModel.installedApps),
    )
    val visibleApps by drawerViewModel.visibleApps.collectAsStateWithLifecycle()
    val query by drawerViewModel.query.collectAsStateWithLifecycle()

    // The drawer covers the whole screen, so the back button is the way out
    // of it when the user does not want to launch anything.
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    val context = LocalContext.current

    // Until the document and the app query have both answered, an empty list
    // means "not loaded yet" rather than "no apps" — showing the empty state
    // then would flash it on every cold start.
    val stillLoading = !appsLoaded || !documentLoaded

    // The stored layout could not be read. Tell the user once, rather than
    // silently showing them a default screen they never configured, and hand
    // the original payload to the platform layer so it can be written to a
    // log file before anything overwrites it.
    LaunchedEffect(loadFailed) {
        if (loadFailed) {
            Toast.makeText(
                context,
                "Your saved layout could not be read, so Liner started with defaults.",
                Toast.LENGTH_LONG,
            ).show()
            viewModel.recoveredPayload?.let(onRecoverLayout)
        }
    }

    // A save that failed must not be silent — the spec asks for a notice.
    LaunchedEffect(writeFailed) {
        if (writeFailed) {
            Toast.makeText(
                context,
                "Your changes could not be saved. Try again.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val theme = document.theme
    val systemTextColor = MaterialTheme.colorScheme.onBackground
    val systemBackgroundColor = MaterialTheme.colorScheme.background
    // The clock takes the theme's text colour, resolved through the same
    // path as every other style decision.
    val chromeColor = StyleResolver.resolveTextColor(theme)?.let { Color(it) } ?: systemTextColor

    val horizontalAlignment = when (theme.alignment) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(
            spec = theme.background,
            wallpaper = wallpaper,
            systemTextColor = systemTextColor,
            systemBackgroundColor = systemBackgroundColor,
        )

        Column(
            Modifier
                .fillMaxSize()
                // The activity draws edge to edge, so the list has to keep
                // clear of the status and navigation bars itself.
                .safeDrawingPadding()
                .padding(
                    horizontal = theme.screenPaddingHdp.dp,
                    vertical = theme.screenPaddingVdp.dp,
                ),
        ) {
            ClockDateHeader(theme, chromeColor)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(theme.itemSpacingDp.dp),
            ) {
                document.sections.forEachIndexed { sectionIndex, section ->
                    val sectionEntries = entries
                        .filter { it.sectionId == section.id }
                        .distinctBy { it.item.id }
                    // An empty section keeps no header — the spec keeps it as
                    // an untitled group, and a blank title is no title at all.
                    val title = section.title?.takeIf { it.isNotBlank() }
                    if (title != null && sectionEntries.isNotEmpty()) {
                        // The section index keeps the key unique even when a
                        // hand-edited layout repeats a section id.
                        item(key = "header-$sectionIndex") {
                            SectionHeaderRow(
                                title = title,
                                style = StyleResolver.resolveSectionHeaderStyle(theme),
                                uppercase = StyleResolver.resolveSectionTitleUppercase(theme),
                                showRule = StyleResolver.resolveSectionTitleRule(theme),
                            )
                        }
                    }
                    items(
                        items = sectionEntries,
                        key = { "$sectionIndex:${it.item.id}" },
                    ) { entry ->
                        // Icons are opt-in: the home screen is icon-free by
                        // default. When they are on, they are rasterised off
                        // the main thread for the same reason as the drawer.
                        val icon by produceState<ImageBitmap?>(
                            initialValue = null,
                            key1 = entry.item.packageName,
                            key2 = theme.showIcons,
                        ) {
                            value = if (theme.showIcons) {
                                withContext(Dispatchers.IO) {
                                    iconCache.icon(entry.item.packageName)
                                }
                            } else {
                                null
                            }
                        }
                        LabelRow(
                            text = entry.displayLabel,
                            style = StyleResolver.resolveItemStyle(theme, entry.item.style),
                            alignment = horizontalAlignment,
                            icon = icon,
                            iconSizeDp = StyleResolver.resolveIconSizeDp(theme),
                            // Until the app query has answered, "not available"
                            // only means "not looked up yet" — greying the row
                            // would report an installed app as missing.
                            enabled = entry.available || !appsLoaded,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLaunch(entry) },
                        )
                    }
                }
            }
        }

        if (entries.isEmpty() && !stillLoading) {
            // Nothing chosen yet: point the user at the Apps tab rather than
            // showing a blank screen. It is drawn last so the button is the
            // top-most element and the tap reaches it.
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No apps on your home screen yet",
                    fontSize = 16.sp,
                    color = systemTextColor,
                )
                TextButton(onClick = { onOpenSettings("apps") }) { Text("Choose apps") }
            }
        }

        // A transparent strip across the bottom of the screen that captures an
        // upward drag and opens the drawer. It sits above the home list, so
        // the drag reaches it rather than scrolling the list.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {},
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount < -12f) drawerOpen = true
                        },
                    )
                },
        )

        // The drawer is drawn last, so it covers the home screen and the
        // gesture zone below it.
        if (drawerOpen) {
            DrawerSheet(
                visibleApps = visibleApps,
                query = query,
                style = theme.drawerStyle,
                iconCache = iconCache,
                background = theme.background,
                wallpaper = wallpaper,
                appsLoaded = appsLoaded,
                systemTextColor = systemTextColor,
                systemBackgroundColor = systemBackgroundColor,
                onQueryChange = drawerViewModel::setQuery,
                onLaunch = { app -> drawerOpen = false; onLaunchApp(app) },
                onOpenSettings = { drawerOpen = false; onOpenSettings("appearance") },
                onDismiss = { drawerOpen = false },
            )
        }
    }
}

@Composable
private fun ClockDateHeader(theme: ThemeTokens, textColor: Color) {
    if (theme.clockStyle == "hidden") return

    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val use24Hour = when (theme.clockFormat) {
        "12h" -> false
        "24h" -> true
        else -> DateFormat.is24HourFormat(context)
    }

    // The clock follows the wall clock. It is refreshed on the minute
    // boundary, which is as often as the displayed time changes.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(zone) {
        while (true) {
            delay(60_000L - now % 60_000L)
            now = System.currentTimeMillis()
        }
    }

    val clockSize = if (theme.clockStyle == "large") 64f else 24f
    val dateSize = if (theme.clockStyle == "large") 18f else 14f
    val clockWeight = if (theme.clockStyle == "large") FontWeight.Light else FontWeight.Normal

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = ClockFormatter.formatTime(now, zone, use24Hour),
            style = TextStyle(fontSize = clockSize.sp, fontWeight = clockWeight, color = textColor),
        )
        val date = ClockFormatter.formatDate(now, zone, theme.dateStyle)
        if (date != null) {
            Text(text = date, style = TextStyle(fontSize = dateSize.sp, color = textColor))
        }
        Spacer(Modifier.height(16.dp))
    }
}
