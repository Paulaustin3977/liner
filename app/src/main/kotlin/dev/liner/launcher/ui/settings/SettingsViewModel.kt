package dev.liner.launcher.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    init {
        viewModelScope.launch { repository.load() }
        // The package manager query is not cheap, so it stays off the main
        // thread.
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
                    SettingsViewModel(repository, appsRepository) as T
            }
    }
}
