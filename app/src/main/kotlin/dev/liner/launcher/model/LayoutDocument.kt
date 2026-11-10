package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class LayoutDocument(
    val version: Int = CURRENT_VERSION,
    val sections: List<Section> = emptyList(),
    val theme: ThemeTokens = ThemeTokens(),
    val hiddenFromDrawer: List<String> = emptyList(),
    val autoPruneUninstalled: Boolean = false,
    /**
     * Whether the user has dismissed the first-run prompt. The prompt is
     * shown once, on the first launch, when Liner is not the default home
     * app — and it stays dismissed afterwards.
     *
     * It lives in the document rather than in a second store because the app
     * keeps no hidden state: everything the user can see is in the document.
     */
    val firstRunPromptDismissed: Boolean = false,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
