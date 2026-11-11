package dev.liner.launcher.ui.home

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.ClockFormatter
import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.ItemBounds
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.StyleResolver
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.model.addItem
import dev.liner.launcher.model.dismissFirstRunPrompt
import dev.liner.launcher.model.moveItem
import dev.liner.launcher.model.removeItem
import dev.liner.launcher.model.removeSection
import dev.liner.launcher.model.renameItem
import dev.liner.launcher.model.renameSection
import dev.liner.launcher.model.sectionById
import dev.liner.launcher.platform.DefaultHomeDetector
import dev.liner.launcher.ui.common.BackgroundLayer
import dev.liner.launcher.ui.common.ItemEditorSheet
import dev.liner.launcher.ui.common.LabelRow
import dev.liner.launcher.ui.common.SectionEditorSheet
import dev.liner.launcher.ui.common.SectionHeaderRow
import dev.liner.launcher.ui.common.dragIndicatorTop
import dev.liner.launcher.ui.common.rememberIsDefaultHome
import dev.liner.launcher.ui.drawer.DrawerSheet
import dev.liner.launcher.ui.drawer.DrawerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * The home screen: a clock, a date, and the user's apps as a list of labels.
 *
 * The screen renders the [HomeViewModel]'s state and reports every interaction
 * back to the caller — it holds no state of its own beyond the clock and the
 * edit mode.
 */
@OptIn(ExperimentalFoundationApi::class)
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

    // Edit mode: a long press on a row arms a drag that reorders the list,
    // and a long press that does not move opens the row's editor.
    val editController = remember { EditModeController() }
    var editingEntry by remember { mutableStateOf<HomeEntry?>(null) }
    var editingSection by remember { mutableStateOf<Section?>(null) }
    // The snackbar that offers to undo a removal, and the scope its action
    // runs in.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The finger's position in root coordinates: the drag maths compares it
    // with the rows' bounds, and the trash zone is hit-tested against it.
    // Only the Y is tracked: the trash zone is hit-tested with the whole
    // pointer offset, so a separate X would be dead state.
    var dragY by remember { mutableFloatStateOf(0f) }
    // Whether the finger is over the trash zone, and where that zone is.
    var overTrash by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    // The section order changes with the document, but the drag callback only
    // restarts when the dragged row changes — so it reads the current order
    // rather than a copy taken when it was built.
    val sectionOrder by rememberUpdatedState(document.sections.map { it.id })

    // The drawer view model belongs to the view model store, so its filter
    // query survives a configuration change.
    val drawerViewModel: DrawerViewModel = viewModel(
        factory = DrawerViewModel.factory(viewModel.document, viewModel.installedApps),
    )
    val visibleApps by drawerViewModel.visibleApps.collectAsStateWithLifecycle()
    val query by drawerViewModel.query.collectAsStateWithLifecycle()

    // Edit mode is left with the back button. It is registered before the
    // drawer's handler so that the drawer — which covers the whole screen —
    // still takes the back button while it is open.
    BackHandler(enabled = editController.active) { editController.exitEditMode() }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    // Removes an item and offers to put it back: a drop on the trash zone is
    // easy to do by accident, and there is no other way back. The removed
    // item and the slot it came from are kept until the snackbar is gone.
    fun removeItemWithUndo(itemId: String) {
        val doc = viewModel.document.value
        val section = doc.sections.firstOrNull { s -> s.items.any { it.id == itemId } }
            ?: return
        val index = section.items.indexOfFirst { it.id == itemId }
        val item = section.items[index]
        // The snackbar names the app the way the home screen does: the user's
        // rename, else the app's own label, and only the package name when
        // nothing else is known.
        val label = viewModel.homeEntries.value.firstOrNull { it.item.id == itemId }?.displayLabel
            ?: item.label
            ?: item.packageName
        viewModel.update { it.removeItem(itemId) }
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Removed $label",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.update { it.addItem(item, section.id, index) }
            }
        }
    }

    // Commits a finished drag: the item moves to the slot the indicator
    // showed, or it is removed when it was dropped on the trash zone.
    fun commitDrop(itemId: String) {
        if (overTrash) {
            removeItemWithUndo(itemId)
            editController.endDrag()
            return
        }
        // The anchor names the visible row the indicator was drawn against.
        // It is read before the drag ends, because ending the drag clears it.
        val anchor = editController.dropAnchor
        when (val target = editController.endDrag()) {
            is DragTarget.InSection, is DragTarget.PreviousSection ->
                viewModel.update { doc -> commitDropToDocument(doc, itemId, target, anchor) }
            null -> Unit
        }
    }

    val context = LocalContext.current

    // Whether Liner is the default home app. The answer is null until it is
    // known: it takes a binder round trip, so it is asked off the main
    // thread. The first-run prompt waits for the answer rather than flashing
    // in and out while it is pending.
    val isDefaultHome = rememberIsDefaultHome(context)

    // The prompt is shown once, on the first launch, and only while Liner is
    // not the default home app.
    val showFirstRunPrompt = documentLoaded &&
        shouldShowFirstRunPrompt(document.firstRunPromptDismissed, isDefaultHome)

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

    Box(
        Modifier
            .fillMaxSize()
            // A tap on empty space leaves edit mode. The rows are drawn
            // above this box and consume their own taps, so tapping a row
            // still opens its editor instead.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = editController.active,
            ) { editController.exitEditMode() },
    ) {
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
            ClockDateHeader(
                theme = theme,
                textColor = chromeColor,
                alignment = horizontalAlignment,
                // The clock dims while edit mode is active, so the list of
                // items is what the user looks at.
                dimmed = editController.active,
            )

            // The first-run prompt sits under the clock, above the list.
            if (showFirstRunPrompt) {
                FirstRunPrompt(
                    onSetAsDefault = { DefaultHomeDetector.requestDefaultHome(context) },
                    onDismiss = { viewModel.update { it.dismissFirstRunPrompt() } },
                )
            }

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
                                // The title follows the same alignment as the
                                // rows below it.
                                alignment = horizontalAlignment,
                                // A long press on the header edits the section:
                                // rename it, or delete it with its items.
                                modifier = Modifier.combinedClickable(
                                    onClick = { },
                                    onLongClick = { editingSection = section },
                                ),
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
                        // The row's top-left corner in root coordinates. A
                        // pointer change is relative to the row, but the drag
                        // maths and the trash zone work in root coordinates.
                        var rowOrigin by remember(entry.item.id) {
                            mutableStateOf(Offset.Zero)
                        }
                        // A row that scrolls out of the list is no longer on
                        // screen, so its bounds must not be counted by the
                        // drag maths.
                        DisposableEffect(entry.item.id) {
                            onDispose { editController.bounds.remove(entry.item.id) }
                        }
                        LabelRow(
                            // An app that is gone reads "unavailable": a
                            // package name on its own reads like a broken
                            // row, not like an app that is no longer there.
                            text = AppCatalog.homeRowLabel(entry, appsLoaded),
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
                                // The rows lift while edit mode is active, and
                                // the row being dragged is faded so the user
                                // can see which one it is: the list is not
                                // reordered under the finger.
                                .scale(if (editController.active) EDIT_MODE_LIFT else 1f)
                                .alpha(
                                    if (entry.item.id == editController.draggingId) {
                                        DRAGGED_ROW_ALPHA
                                    } else {
                                        1f
                                    },
                                )
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInRoot()
                                    val size = coordinates.size
                                    rowOrigin = position
                                    editController.bounds[entry.item.id] = ItemBounds(
                                        id = entry.item.id,
                                        sectionId = entry.sectionId,
                                        top = position.y,
                                        bottom = position.y + size.height,
                                    )
                                }
                                .pointerInput(entry.item.id) {
                                    // The long press is detected here rather
                                    // than by a combinedClickable: the long
                                    // press of a clickable consumes the rest
                                    // of the pointer stream, which would
                                    // cancel the drag it is meant to start.
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            editController.startDrag(entry.item.id)
                                            dragY = rowOrigin.y + offset.y
                                            overTrash = false
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val finger = rowOrigin + change.position
                                            dragY = finger.y
                                            overTrash = trashBounds?.contains(finger) == true
                                            if (overTrash) {
                                                // The item is dropped, not moved,
                                                // so there is no slot to show.
                                                editController.clearTarget()
                                            } else {
                                                editController.updateDrag(dragY, sectionOrder)
                                            }
                                        },
                                        onDragEnd = { commitDrop(entry.item.id) },
                                        onDragCancel = { editController.endDrag() },
                                    )
                                }
                                .clickable {
                                    // In edit mode a tap opens the item's
                                    // editor instead of launching it.
                                    if (editController.active) {
                                        editingEntry = entry
                                    } else {
                                        onLaunch(entry)
                                    }
                                },
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

        // The insertion indicator: a line at the slot the dragged item would
        // land in. It floats over the rows, so it is drawn after the list.
        val dragTarget = editController.dragTarget
        if (dragTarget != null) {
            val indicatorTop = dragIndicatorTop(
                target = dragTarget,
                bounds = editController.bounds.values.toList(),
                draggedId = editController.draggingId,
            )
            Box(
                Modifier
                    .offset { IntOffset(0, indicatorTop.toInt()) }
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    // The theme's own text colour, so the line is visible on
                    // a light background as well as a dark one.
                    .background(chromeColor),
            )
        }

        // The trash zone. It only exists in edit mode, and dropping a row on
        // it removes that row from the home screen.
        if (editController.active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(72.dp)
                    .onGloballyPositioned { trashBounds = it.boundsInRoot() }
                    .background(Color(0xFFB3261E), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Drop here to remove", color = Color.White)
            }
        }

        // The snackbar that offers to undo a removal. It is drawn above the
        // trash zone, which only exists in edit mode.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp),
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
                textColor = chromeColor,
                systemTextColor = systemTextColor,
                systemBackgroundColor = systemBackgroundColor,
                onQueryChange = drawerViewModel::setQuery,
                onLaunch = { app -> drawerOpen = false; onLaunchApp(app) },
                onOpenSettings = { drawerOpen = false; onOpenSettings("appearance") },
                onDismiss = { drawerOpen = false },
            )
        }
    }

    // The editors for the item and the section. They are dialogs, so they
    // sit on top of everything else.
    editingEntry?.let { entry ->
        ItemEditorSheet(
            entry = entry,
            sections = document.sections.map { it.id to it.title },
            onRename = { label ->
                viewModel.update { doc -> doc.renameItem(entry.item.id, label) }
            },
            onMoveToSection = { sectionId ->
                viewModel.update { doc ->
                    doc.moveItem(
                        entry.item.id,
                        sectionId,
                        doc.sectionById(sectionId)?.items?.size ?: 0,
                    )
                }
            },
            onRemove = { removeItemWithUndo(entry.item.id) },
            onDismiss = { editingEntry = null },
        )
    }

    editingSection?.let { section ->
        SectionEditorSheet(
            title = section.title,
            itemCount = section.items.size,
            onRename = { title ->
                viewModel.update { doc -> doc.renameSection(section.id, title) }
            },
            onDelete = { viewModel.update { doc -> doc.removeSection(section.id) } },
            onDismiss = { editingSection = null },
        )
    }
}

@Composable
private fun ClockDateHeader(
    theme: ThemeTokens,
    textColor: Color,
    alignment: Alignment.Horizontal,
    dimmed: Boolean,
) {
    if (theme.clockStyle == "hidden") return

    val context = LocalContext.current
    // The zone is read on every recomposition rather than remembered: a
    // time-zone change does not recreate the activity, so a remembered zone
    // would keep showing the old zone's time. The clock ticks every minute,
    // so the zone is re-read at least that often.
    val zone = ZoneId.systemDefault()
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

    Column(
        Modifier
            .fillMaxWidth()
            // The clock dims while edit mode is active.
            .alpha(if (dimmed) EDIT_MODE_DIM_ALPHA else 1f),
        horizontalAlignment = alignment,
    ) {
        Text(
            text = ClockFormatter.formatTime(now, zone, use24Hour),
            style = TextStyle(
                fontSize = clockSize.sp,
                fontWeight = clockWeight,
                color = textColor,
                textAlign = alignment.textAlign(),
            ),
        )
        val date = ClockFormatter.formatDate(now, zone, theme.dateStyle)
        if (date != null) {
            Text(
                text = date,
                style = TextStyle(
                    fontSize = dateSize.sp,
                    color = textColor,
                    textAlign = alignment.textAlign(),
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The text alignment that goes with a horizontal alignment.
 *
 * The clock and the date are placed by the column's alignment; the text
 * inside them follows it too, so a date that wraps onto a second line is
 * aligned the same way as the first.
 */
private fun Alignment.Horizontal.textAlign(): TextAlign = when (this) {
    Alignment.CenterHorizontally -> TextAlign.Center
    Alignment.End -> TextAlign.End
    else -> TextAlign.Start
}

/** The scale the rows are drawn at while edit mode is active. */
private const val EDIT_MODE_LIFT = 1.02f

/** The alpha the clock is drawn at while edit mode is active. */
private const val EDIT_MODE_DIM_ALPHA = 0.5f

/** The alpha the row that is being dragged is drawn at. */
private const val DRAGGED_ROW_ALPHA = 0.4f
