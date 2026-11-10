package dev.liner.launcher.platform

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object DefaultHomeDetector {

    /**
     * True when Liner is currently the default home app.
     *
     * On API 29+ the HOME role is the authoritative answer. Below that, the
     * *resolved* default home activity is the answer — note that
     * `queryIntentActivities` is not, because it returns every activity that
     * handles the intent, and Liner is always among them.
     */
    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = runCatching {
                context.getSystemService(RoleManager::class.java)
            }.getOrNull()
            val held = roleManager?.let {
                runCatching { it.isRoleHeld(RoleManager.ROLE_HOME) }.getOrNull()
            }
            if (held != null) return held
        }

        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == context.packageName
        } catch (_: Throwable) {
            // The query is a package-manager round trip; if it fails the
            // launcher cannot prove it is the default home app, but it must
            // not crash.
            false
        }
    }

    /** Fires the system flow that lets the user make Liner the default home app. */
    fun requestDefaultHome(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
            if (roleManager != null) {
                // Nothing to do if Liner already holds the role — sending the
                // user to a settings screen with nothing to change is worse
                // than doing nothing.
                if (isRoleHeld(roleManager)) return
                if (isRoleAvailable(roleManager)) {
                    runCatching {
                        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    }.onFailure { openHomeSettings(context) }
                    return
                }
            }
        }
        openHomeSettings(context)
    }

    /**
     * The role queries are remote calls, so a failure here is treated as
     * "unknown" rather than crashing the launcher.
     */
    private fun isRoleHeld(roleManager: RoleManager): Boolean = runCatching {
        roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    private fun isRoleAvailable(roleManager: RoleManager): Boolean = runCatching {
        roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    private fun openHomeSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
