package dev.liner.launcher.model

/**
 * Removes the item from wherever it currently lives, then inserts it at
 * [toIndex] within the target section's post-removal item list.
 * An unknown [toSectionId] or [itemId] leaves the document unchanged.
 *
 * A layout that was edited by hand can repeat a section id or an item id,
 * so the removal takes the item out of *every* section that holds it and the
 * insert goes into the *first* section whose id matches — the same rules
 * [removeItem] follows. A repeated section id must not put the item in two
 * sections, and a repeated item id must not leave a copy behind.
 */
fun LayoutDocument.moveItem(itemId: String, toSectionId: String, toIndex: Int): LayoutDocument {
    val source = sections.firstOrNull { s -> s.items.any { it.id == itemId } } ?: return this
    if (sections.none { it.id == toSectionId }) return this
    val item = source.items.first { it.id == itemId }

    val withoutItem = sections.map { s ->
        if (s.items.any { it.id == itemId }) s.copy(items = s.items.filterNot { it.id == itemId }) else s
    }

    // The first section with a matching id takes the item — a repeated
    // section id must not get a second copy of it.
    val target = withoutItem.indexOfFirst { it.id == toSectionId }

    return withoutItem.mapIndexed { index, s ->
        if (index != target) {
            s
        } else {
            val safeIndex = toIndex.coerceIn(0, s.items.size)
            s.copy(items = s.items.toMutableList().apply { add(safeIndex, item) })
        }
    }.let { copy(sections = it) }
}

fun LayoutDocument.removeItem(itemId: String): LayoutDocument =
    copy(sections = sections.map { s -> s.copy(items = s.items.filterNot { it.id == itemId }) })

fun LayoutDocument.renameItem(itemId: String, label: String?): LayoutDocument =
    copy(sections = sections.map { s ->
        s.copy(items = s.items.map { i ->
            if (i.id == itemId) i.copy(label = label?.trim()?.takeIf { it.isNotEmpty() }) else i
        })
    })

fun LayoutDocument.setItemStyle(itemId: String, style: ItemStyle?): LayoutDocument =
    copy(sections = sections.map { s ->
        s.copy(items = s.items.map { i -> if (i.id == itemId) i.copy(style = style) else i })
    })

fun LayoutDocument.addItem(item: HomeItem, sectionId: String, index: Int = Int.MAX_VALUE): LayoutDocument {
    if (sections.none { it.id == sectionId }) return this
    if (sections.any { s -> s.items.any { it.id == item.id } }) return this

    return copy(sections = sections.map { s ->
        if (s.id != sectionId) {
            s
        } else {
            val safeIndex = index.coerceIn(0, s.items.size)
            s.copy(items = s.items.toMutableList().apply { add(safeIndex, item) })
        }
    })
}

/**
 * Applies a desired flat order of home item ids. Items keep their section,
 * but their order inside that section follows [flatOrder]. Ids not present in
 * [flatOrder] keep their previous relative position at the end of their
 * section — the tiebreaker is the item's previous index, not its id.
 */
fun LayoutDocument.reorderHomeItems(flatOrder: List<String>): LayoutDocument {
    if (flatOrder.isEmpty()) return this
    val position = flatOrder.withIndex().associate { (index, id) -> id to index }

    return copy(sections = sections.map { s ->
        val previousIndex = s.items.withIndex().associate { (index, item) -> item.id to index }
        val ordered = s.items.sortedWith(
            compareBy(
                { position[it.id] ?: Int.MAX_VALUE },
                { previousIndex[it.id] ?: Int.MAX_VALUE },
            ),
        )
        s.copy(items = ordered)
    })
}

/**
 * Adds a section at the end of the document.
 *
 * A layout that was edited by hand can already hold a section with this id,
 * so a duplicate is refused rather than added — the same rule [addItem]
 * follows. Two sections with the same id would show the same apps.
 */
fun LayoutDocument.addSection(id: String, title: String? = null): LayoutDocument {
    if (sections.any { it.id == id }) return this
    return copy(sections = sections + Section(id = id, title = title?.trim()?.takeIf { it.isNotEmpty() }))
}

fun LayoutDocument.renameSection(sectionId: String, title: String?): LayoutDocument =
    copy(sections = sections.map { s ->
        if (s.id == sectionId) s.copy(title = title?.trim()?.takeIf { it.isNotEmpty() }) else s
    })

fun LayoutDocument.removeSection(sectionId: String): LayoutDocument =
    copy(sections = sections.filterNot { it.id == sectionId })

fun LayoutDocument.moveSection(sectionId: String, toIndex: Int): LayoutDocument {
    val from = sections.indexOfFirst { it.id == sectionId }
    if (from < 0) return this
    val mutable = sections.toMutableList()
    val moved = mutable.removeAt(from)
    mutable.add(toIndex.coerceIn(0, mutable.size), moved)
    return copy(sections = mutable)
}

fun LayoutDocument.setHiddenFromDrawer(packageName: String, hidden: Boolean): LayoutDocument =
    if (hidden) {
        if (packageName in hiddenFromDrawer) this
        else copy(hiddenFromDrawer = hiddenFromDrawer + packageName)
    } else {
        copy(hiddenFromDrawer = hiddenFromDrawer.filterNot { it == packageName })
    }

fun LayoutDocument.setAutoPruneUninstalled(enabled: Boolean): LayoutDocument =
    copy(autoPruneUninstalled = enabled)

/**
 * Records that the user has dismissed the first-run prompt, so it does not
 * come back.
 */
fun LayoutDocument.dismissFirstRunPrompt(): LayoutDocument =
    copy(firstRunPromptDismissed = true)

/**
 * Removes every home item whose package is not in [installedPackages].
 *
 * A no-op when auto-pruning is off, and a no-op when nothing is stale.
 *
 * The caller MUST pass a list that really came from the package manager.
 * A query that failed, or one that came back empty because the OS filtered
 * the package visibility, is not evidence that the apps were uninstalled —
 * pruning on it would delete the user's whole home screen.
 */
fun LayoutDocument.pruneUninstalledApps(installedPackages: Set<String>): LayoutDocument {
    if (!autoPruneUninstalled) return this
    val stale = sections.flatMap { it.items }.filter { it.packageName !in installedPackages }
    if (stale.isEmpty()) return this
    return stale.fold(this) { document, item -> document.removeItem(item.id) }
}

fun LayoutDocument.updateTheme(transform: (ThemeTokens) -> ThemeTokens): LayoutDocument =
    copy(theme = transform(theme))

fun LayoutDocument.itemById(itemId: String): HomeItem? =
    sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == itemId }

fun LayoutDocument.sectionById(sectionId: String): Section? =
    sections.firstOrNull { it.id == sectionId }
