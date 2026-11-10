package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class LayoutDocument(
    val version: Int = CURRENT_VERSION,
    val sections: List<Section> = emptyList(),
    val theme: ThemeTokens = ThemeTokens(),
    val hiddenFromDrawer: List<String> = emptyList(),
    val autoPruneUninstalled: Boolean = false,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
