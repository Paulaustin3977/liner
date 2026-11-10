package dev.liner.launcher.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.liner.launcher.platform.DefaultHomeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether Liner is the default home app, or null while the answer is still
 * unknown.
 *
 * The answer is null until it is known: it takes a round trip to the role
 * manager or the package manager, so it is asked off the main thread and
 * after the composition. Nothing that depends on it may flash before the
 * answer arrives.
 *
 * The question is asked once when the caller appears, and again whenever the
 * screen comes back to the foreground: the user leaves for the system's
 * home-app settings and comes back, and the answer may have changed.
 */
@Composable
fun rememberIsDefaultHome(context: Context): Boolean? {
    var isDefaultHome by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Asked once when the caller appears.
    LaunchedEffect(context) {
        isDefaultHome = withContext(Dispatchers.IO) {
            DefaultHomeDetector.isDefaultHome(context)
        }
    }

    // …and again whenever the screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    isDefaultHome = withContext(Dispatchers.IO) {
                        DefaultHomeDetector.isDefaultHome(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return isDefaultHome
}
