package dev.liner.launcher.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
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

                // A save that failed must not be silent — the user gets the
                // same notice the home screen gives.
                LaunchedEffect(writeFailed) {
                    if (writeFailed) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Your changes could not be saved. Try again.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                SettingsScreen(
                    document = document,
                    installedApps = apps,
                    initialTab = initialTab,
                    onUpdate = viewModel::update,
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
