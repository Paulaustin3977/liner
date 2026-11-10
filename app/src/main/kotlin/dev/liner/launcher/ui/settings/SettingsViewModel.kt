package dev.liner.launcher.ui.settings

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The settings screen's state: the layout document and the installed apps.
 *
 * The view model owns no storage of its own — every change is handed to the
 * repository, which is the only thing that writes.
 */
class SettingsViewModel(
    private val repository: LayoutRepository,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    val document: StateFlow<LayoutDocument> = repository.document

    /** True when the last save failed; the screen turns it into a notice. */
    val writeFailed: StateFlow<Boolean> = repository.writeFailed

    /**
     * True when the stored layout could not be read. The settings activity is
     * exported with CATEGORY_LAUNCHER, so it can be the process's first
     * activity and the screen that performs the only load — it has to tell
     * the user their layout was reset, the way the home screen does.
     */
    val loadFailed: StateFlow<Boolean> = repository.loadFailed

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    /**
     * Whether the installed-app list has finished its query. Until it has,
     * an empty list means "not loaded yet", not "no apps installed" — the
     * apps tab must not show an empty list during that window.
     */
    private val _appsLoaded = MutableStateFlow(false)
    val appsLoaded: StateFlow<Boolean> = _appsLoaded.asStateFlow()

    /**
     * The home items that were taken off the home screen, by package name.
     *
     * An app that is put back must keep the label and the style the user
     * gave it, so the item itself is kept — not just the knowledge that the
     * app was there.
     *
     * The set belongs to the view model rather than to the apps tab: the
     * settings activity is recreated on a rotation, and state kept in a
     * composable would go with it — the next check of the app would then
     * lose its customisation.
     */
    private val _removedItems = mutableStateMapOf<String, RemovedHomeItem>()

    /** The home items that were taken off the home screen, by package name. */
    val removedItems: Map<String, RemovedHomeItem> = _removedItems

    /**
     * Remembers the item that was taken off the home screen, with the
     * section it was in and its place in it.
     */
    fun rememberRemoved(packageName: String, item: HomeItem, sectionId: String, index: Int) {
        _removedItems[packageName] = RemovedHomeItem(item, sectionId, index)
    }

    /** Forgets an app that is back on the home screen. */
    fun forgetRemoved(packageName: String) {
        _removedItems.remove(packageName)
    }

    init {
        // The repository reads the store once per process, so this is a no-op
        // when the home screen has already loaded it — and it must never
        // clobber an edit that has not been written back yet.
        viewModelScope.launch { repository.load() }
        refreshApps()
    }

    fun update(transform: (LayoutDocument) -> LayoutDocument) = repository.update(transform)

    /**
     * Queries the package manager again.
     *
     * The query is not cheap, so it stays off the main thread — the same
     * way the initial load does it. An app installed while the settings
     * were open is not in the list until the user asks for it.
     */
    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _installedApps.value = appsRepository.loadInstalledApps().apps
            } finally {
                // Even a failed query means "we have looked" — without this
                // the UI would wait forever before showing its states.
                _appsLoaded.value = true
            }
        }
    }

    companion object {
        fun factory(repository: LayoutRepository, appsRepository: InstalledAppsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(repository, appsRepository) as T
            }
    }
}
