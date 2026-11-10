package dev.liner.launcher.model

object AppFilter {
    fun filter(
        apps: List<InstalledApp>,
        query: String,
        customLabels: Map<String, String> = emptyMap(),
    ): List<InstalledApp> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return apps
        return apps.filter { app ->
            app.systemLabel.contains(trimmed, ignoreCase = true) ||
                customLabels[app.packageName]?.contains(trimmed, ignoreCase = true) == true
        }
    }
}
