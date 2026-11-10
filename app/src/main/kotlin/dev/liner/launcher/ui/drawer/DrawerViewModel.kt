package dev.liner.launcher.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.AppFilter
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The drawer's state: the filter query, and the apps it leaves visible.
 *
 * The apps are filtered in the order [AppCatalog.drawerApps] returns them, so
 * the alphabetical order survives the filter — [AppFilter] keeps the order of
 * the list it is given, and filtering an unsorted list would lose it.
 */
class DrawerViewModel(
    document: StateFlow<LayoutDocument>,
    installedApps: StateFlow<List<InstalledApp>>,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    val query: StateFlow<String> = _query

    val visibleApps: StateFlow<List<InstalledApp>> =
        combine(document, installedApps, _query) { doc, apps, q ->
            val visible = AppCatalog.drawerApps(doc, apps)
            AppFilter.filter(visible, q, AppCatalog.customLabels(doc))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    companion object {
        /**
         * The drawer view model belongs to the view model store, so it
         * survives a configuration change and is cleared with the screen.
         */
        fun factory(
            document: StateFlow<LayoutDocument>,
            installedApps: StateFlow<List<InstalledApp>>,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DrawerViewModel(document, installedApps) as T
        }
    }
}
