package dev.liner.launcher.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.pruneUninstalledApps
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The home screen's state: the layout document, the installed apps, and the
 * home entries derived from the two.
 */
class HomeViewModel(
    private val repository: LayoutRepository,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    val document: StateFlow<LayoutDocument> = repository.document

    val writeFailed: StateFlow<Boolean> = repository.writeFailed

    val loadFailed: StateFlow<Boolean> = repository.loadFailed

    /** The unreadable payload from a failed load, kept so it can be logged. */
    val recoveredPayload: String?
        get() = repository.recoveredPayload

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    val homeEntries: StateFlow<List<HomeEntry>> =
        combine(document, _installedApps) { doc, apps ->
            AppCatalog.homeEntries(doc, apps.associateBy { it.packageName })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether the installed-app list has finished its first query. Until it
     * has, an empty list means "not loaded yet", not "no apps" — the UI must
     * not flash the empty state or grey out every row during that window.
     */
    private val _appsLoaded = MutableStateFlow(false)
    val appsLoaded: StateFlow<Boolean> = _appsLoaded.asStateFlow()

    /**
     * Whether the saved document has finished its first load.
     *
     * The signal is the repository's: the store is read once per process, so
     * this screen's own [LayoutRepository.load] call can return before the
     * document is in memory — when the settings screen claimed the read
     * first.
     */
    val documentLoaded: StateFlow<Boolean> = repository.documentLoaded

    init {
        viewModelScope.launch { repository.load() }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // The prune may not run before the stored document is in
                // memory: updating before then would persist the DEFAULT
                // document over the user's stored layout. The signal comes
                // from the repository, because a second caller's load()
                // returns immediately without the document being loaded.
                repository.documentLoaded.first { it }

                val load = appsRepository.loadInstalledApps()
                _installedApps.value = load.apps
                // Auto-pruning removes home entries whose app is gone — but
                // only from a query that really answered. A failed or
                // filtered query must never be read as "every app was
                // uninstalled", or the whole home screen would be deleted.
                // A failed READ is not evidence either: the in-memory
                // document is then the default one, and writing it back
                // would destroy the layout still on disk.
                if (!load.wasFallback && !repository.loadFailed.value) {
                    val installed = load.apps.mapTo(HashSet()) { it.packageName }
                    repository.update { doc -> doc.pruneUninstalledApps(installed) }
                }
            } finally {
                // Even a failed query means "we have looked" — without this
                // the UI would wait forever before showing its states.
                _appsLoaded.value = true
            }
        }
    }

    fun update(transform: (LayoutDocument) -> LayoutDocument) = repository.update(transform)

    companion object {
        fun factory(repository: LayoutRepository, appsRepository: InstalledAppsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, appsRepository) as T
            }
    }
}
