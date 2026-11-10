package dev.liner.launcher.model

data class InstalledApp(
    val packageName: String,
    val activityClass: String,
    val systemLabel: String,
)
