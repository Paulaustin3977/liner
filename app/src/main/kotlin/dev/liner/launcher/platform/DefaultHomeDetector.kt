package dev.liner.launcher.platform

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object DefaultHomeDetector {

    /** True when Liner is currently the resolved home app. */
    fun isDefaultHome(context: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        resolved.any { it.activityInfo?.packageName == context.packageName }
    } catch (_: Throwable) {
        // The query is a package-manager round trip; if it fails the launcher
        // cannot prove it is the default home app, but it must not crash.
        false
    }

    /** Fires the system flow that lets the user make Liner the default home app. */
    fun requestDefaultHome(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
            if (roleManager != null && canRequestHomeRole(roleManager)) {
                runCatching {
                    context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }.onFailure { openHomeSettings(context) }
                return
            }
        }
        openHomeSettings(context)
    }

    /**
     * True when the system offers the home role and Liner does not hold it yet.
     *
     * The role queries are remote calls, so a failure here falls back to the
     * home settings screen rather than crashing the launcher.
     */
    private fun canRequestHomeRole(roleManager: RoleManager): Boolean = runCatching {
        roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    private fun openHomeSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
