package dev.liner.launcher.model

/**
 * Removes the item from wherever it currently lives, then inserts it at
 * [toIndex] within the target section's post-removal item list.
 * An unknown [toSectionId] or [itemId] leaves the document unchanged.
 */
fun LayoutDocument.moveItem(itemId: String, toSectionId: String, toIndex: Int): LayoutDocument {
    val source = sections.firstOrNull { s -> s.items.any { it.id == itemId } } ?: return this
    if (sections.none { it.id == toSectionId }) return this
    val item = source.items.first { it.id == itemId }

    val withoutItem = sections.map { s ->
        if (s.id == source.id) s.copy(items = s.items.filterNot { it.id == itemId }) else s
    }

    return withoutItem.map { s ->
        if (s.id != toSectionId) {
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
 * [flatOrder] are placed after the listed ones, ordered by id.
 */
fun LayoutDocument.reorderHomeItems(flatOrder: List<String>): LayoutDocument {
    if (flatOrder.isEmpty()) return this
    val position = flatOrder.withIndex().associate { (index, id) -> id to index }

    return copy(sections = sections.map { s ->
        val ordered = s.items.sortedWith(
            compareBy(
                { position[it.id] ?: Int.MAX_VALUE },
                { it.id },
            ),
        )
        s.copy(items = ordered)
    })
}

fun LayoutDocument.addSection(id: String, title: String? = null): LayoutDocument =
    copy(sections = sections + Section(id = id, title = title?.trim()?.takeIf { it.isNotEmpty() }))

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

fun LayoutDocument.updateTheme(transform: (ThemeTokens) -> ThemeTokens): LayoutDocument =
    copy(theme = transform(theme))

fun LayoutDocument.itemById(itemId: String): HomeItem? =
    sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == itemId }

fun LayoutDocument.sectionById(sectionId: String): Section? =
    sections.firstOrNull { it.id == sectionId }
