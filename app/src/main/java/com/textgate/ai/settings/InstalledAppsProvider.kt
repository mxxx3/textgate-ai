package com.textgate.ai.settings

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import com.textgate.ai.security.AppBlocklist

/** [icon] is the app's REAL launcher icon loaded straight from
 * PackageManager (see [InstalledAppsProvider.listLaunchableApps]) — never a
 * fabricated or generic graphic. It is null only when the platform itself
 * could not supply one for that app, in which case the UI falls back to a
 * neutral placeholder glyph (see `R.drawable.ic_default_app`) rather than
 * inventing a stand-in that could be mistaken for that app's real icon. */
data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable?)

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

                // Loaded straight from PackageManager's own resolved
                // ResolveInfo — the same real icon the OS launcher itself
                // would show for this app. Wrapped in try/catch since icon
                // resource loading can, in rare cases (a malformed or
                // uninstalled-mid-query APK), throw; a failure here is not
                // fatal to listing the app, just to showing its icon.
                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (_: Exception) {
                    null
                }

                LaunchableApp(packageName = packageName, label = label, icon = icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
