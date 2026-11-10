package dev.liner.launcher.platform

import android.app.Application

class LinerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
