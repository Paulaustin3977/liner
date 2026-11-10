package dev.liner.launcher.model

object AppCatalog {
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
                sectionTitle = section.title,
            )
        }
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
