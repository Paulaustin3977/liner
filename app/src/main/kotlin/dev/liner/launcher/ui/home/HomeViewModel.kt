package dev.liner.launcher.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    init {
        viewModelScope.launch { repository.load() }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = appsRepository.loadInstalledApps()
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
