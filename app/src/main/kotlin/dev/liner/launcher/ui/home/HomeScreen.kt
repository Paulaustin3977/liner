package dev.liner.launcher.ui.home

import android.graphics.drawable.Drawable
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
import dev.liner.launcher.ui.common.toColor
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
    wallpaper: Drawable?,
    iconCache: IconCache,
    onLaunch: (HomeEntry) -> Unit,
    onOpenSettings: (String?) -> Unit,
    onRecoverLayout: (String) -> Unit,
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val entries by viewModel.homeEntries.collectAsStateWithLifecycle()
    val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()

    val context = LocalContext.current

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

    val theme = document.theme
    val systemTextColor = MaterialTheme.colorScheme.onBackground

    val horizontalAlignment = when (theme.alignment) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(spec = theme.background, wallpaper = wallpaper, systemTextColor = systemTextColor)

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
            ClockDateHeader(theme, systemTextColor)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(theme.itemSpacingDp.dp),
            ) {
                document.sections.forEachIndexed { sectionIndex, section ->
                    val title = section.title
                    if (title != null) {
                        // The section index keeps the key unique even when a
                        // hand-edited layout repeats a section id.
                        item(key = "header-$sectionIndex") {
                            SectionHeaderRow(
                                title = title,
                                style = StyleResolver.resolveSectionHeaderStyle(theme),
                                uppercase = theme.sectionTitleUppercase,
                                showRule = theme.sectionTitleRule,
                            )
                        }
                    }
                    items(
                        items = entries.filter { it.sectionId == section.id }.distinctBy { it.item.id },
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
                            iconSizeDp = theme.iconSizeDp,
                            enabled = entry.available,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLaunch(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockDateHeader(theme: ThemeTokens, systemTextColor: Color) {
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
    // The clock follows the text colour of the theme.
    val color = theme.textColor.toColor(systemTextColor)

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = ClockFormatter.formatTime(now, zone, use24Hour),
            style = TextStyle(fontSize = clockSize.sp, fontWeight = clockWeight, color = color),
        )
        val date = ClockFormatter.formatDate(now, zone, theme.dateStyle)
        if (date != null) {
            Text(text = date, style = TextStyle(fontSize = dateSize.sp, color = color))
        }
        Spacer(Modifier.height(16.dp))
    }
}
