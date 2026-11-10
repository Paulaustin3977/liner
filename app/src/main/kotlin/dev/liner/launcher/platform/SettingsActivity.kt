package dev.liner.launcher.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Settings") }
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
