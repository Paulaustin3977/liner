package dev.liner.launcher.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liner.launcher.ui.settings.SettingsScreen
import dev.liner.launcher.ui.settings.SettingsViewModel

class SettingsActivity : ComponentActivity() {

    private val container by lazy { (application as LinerApp).container }

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory(container.repository, container.appsRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getStringExtra(EXTRA_TAB)

        setContent {
            LinerTheme {
                val document by viewModel.document.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()
                val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
                val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
                val appsLoaded by viewModel.appsLoaded.collectAsStateWithLifecycle()

                SettingsScreen(
                    document = document,
                    installedApps = apps,
                    removedItems = viewModel.removedItems,
                    initialTab = initialTab,
                    writeFailed = writeFailed,
                    loadFailed = loadFailed,
                    appsLoaded = appsLoaded,
                    onUpdate = viewModel::update,
                    onRefresh = viewModel::refreshApps,
                    onRememberRemoved = viewModel::rememberRemoved,
                    onForgetRemoved = viewModel::forgetRemoved,
                )
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"

        fun intent(context: Context, tab: String? = null): Intent =
            Intent(context, SettingsActivity::class.java).apply {
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }
    }
}
