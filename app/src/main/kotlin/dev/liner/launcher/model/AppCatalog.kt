package dev.liner.launcher.model

object AppCatalog {

    /**
     * What a home row reads when its app is gone and it has no name of its
     * own.
     *
     * The package name is not a label: a row that reads "com.spotify.mobile"
     * looks like a broken row, not like an app that is no longer installed.
     */
    const val UNAVAILABLE_LABEL = "unavailable"

    fun homeEntries(
        document: LayoutDocument,
        installed: Map<String, InstalledApp>,
    ): List<HomeEntry> = document.sections.flatMap { section ->
        section.items.map { item ->
            val app = installed[item.packageName]
            HomeEntry(
                item = item,
                displayLabel = item.label ?: app?.systemLabel ?: item.packageName,
                available = app != null,
                sectionId = section.id,
            )
        }
    }

    /**
     * The label a home row shows.
     *
     * The user's rename always wins — it is theirs, and it survives the app
     * being uninstalled. Otherwise the app's own label, and "unavailable"
     * when the app is gone.
     *
     * Before the app query has answered, an app that is not in the list may
     * still be installed, so its package name is shown until then: a row
     * that says "unavailable" and then changes its mind is worse than a row
     * that shows a package name for a moment.
     */
    fun homeRowLabel(entry: HomeEntry, appsLoaded: Boolean): String = when {
        entry.item.label != null -> entry.item.label
        entry.available -> entry.displayLabel
        appsLoaded -> UNAVAILABLE_LABEL
        else -> entry.displayLabel
    }

    fun drawerApps(
        document: LayoutDocument,
        installed: List<InstalledApp>,
    ): List<InstalledApp> {
        val hidden = document.hiddenFromDrawer.toSet()
        return installed
            .filterNot { it.packageName in hidden }
            .sortedWith(compareBy({ it.systemLabel.lowercase() }, { it.packageName }))
    }

    fun customLabels(document: LayoutDocument): Map<String, String> = buildMap {
        document.sections.forEach { section ->
            section.items.forEach { item ->
                item.label?.let { put(item.packageName, it) }
            }
        }
    }
}
