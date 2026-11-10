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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /** Whether the saved document has finished its first load. */
    private val _documentLoaded = MutableStateFlow(false)
    val documentLoaded: StateFlow<Boolean> = _documentLoaded.asStateFlow()

    /**
     * Completes once the stored document has been read into memory.
     *
     * The auto-prune writes the document, so it may not run before that
     * read: a write that lands first would persist the *default* document
     * and throw the user's layout away.
     */
    private val documentRead = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            try {
                repository.load()
            } finally {
                _documentLoaded.value = true
                documentRead.complete(Unit)
            }
        }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = appsRepository.loadInstalledApps()
                _installedApps.value = apps
                // Auto-pruning removes home entries whose app is gone — but
                // only from a query that really answered. A failed or
                // filtered query must never be read as "every app was
                // uninstalled", or the whole home screen would be deleted.
                if (!appsRepository.lastLoadWasFallback()) {
                    val installed = apps.mapTo(HashSet()) { it.packageName }
                    // The prune writes the document, so it waits for the
                    // stored document to be read: pruning before that would
                    // persist the default document over the user's layout.
                    documentRead.await()
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
