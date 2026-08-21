package com.textgate.ai.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.textgate.ai.security.AppBlocklist

data class LaunchableApp(val packageName: String, val label: String)

/**
 * Lists installed, user-launchable apps using the declarative <queries>
 * filter in AndroidManifest.xml — NOT the QUERY_ALL_PACKAGES permission,
 * which this app never requests. Apps in [AppBlocklist] (password
 * managers, authenticators, banking-keyword matches, OS security
 * surfaces) and this app itself are excluded from the list entirely, so
 * the user cannot accidentally allow-list something that must never be
 * allow-listed.
 */
object InstalledAppsProvider {

    fun listLaunchableApps(context: Context): List<LaunchableApp> {
        val packageManager = context.packageManager
        val ownPackage = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolveInfos = try {
            packageManager.queryIntentActivities(launcherIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        return resolveInfos
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                if (packageName == ownPackage) return@mapNotNull null
                if (AppBlocklist.isBlocked(packageName, ownPackage)) return@mapNotNull null

                val label = try {
                    resolveInfo.loadLabel(packageManager)?.toString()
                } catch (_: Exception) {
                    null
                } ?: packageName

                LaunchableApp(packageName = packageName, label = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
