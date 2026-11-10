package dev.liner.launcher.ui.home

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.ClockFormatter
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.StyleResolver
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.ui.common.BackgroundLayer
import dev.liner.launcher.ui.common.LabelRow
import dev.liner.launcher.ui.common.SectionHeaderRow
import kotlinx.coroutines.delay
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
    onOpenSettings: (String?) -> Unit,
    onRecoverLayout: (String) -> Unit,
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val entries by viewModel.homeEntries.collectAsStateWithLifecycle()
    val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
    val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
    val appsLoaded by viewModel.appsLoaded.collectAsStateWithLifecycle()
    val documentLoaded by viewModel.documentLoaded.collectAsStateWithLifecycle()

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
                        // default. The cache makes the lookup cheap, and a
                        // failed load simply leaves the row without an icon.
                        val icon = if (theme.showIcons) {
                            iconCache.icon(entry.item.packageName)
                        } else {
                            null
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
