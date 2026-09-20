package com.textgate.ai.setup

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/**
 * Opens the system/OEM screens used during setup.
 *
 * Two important rules are enforced here:
 * 1. battery state is read from Android's real PowerManager state;
 * 2. Autostart is only exposed when a manufacturer-specific Activity can
 *    actually be resolved on this device. There is no Android-wide
 *    "autostart enabled" API, so the app never pretends to verify that switch.
 */
internal object SetupSettingsLauncher {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return false
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Requests Android's real per-app Doze exemption when it is not already
     * granted. If the direct request screen is unavailable, falls back to the
     * system battery-optimization list and finally to this app's details page.
     */
    fun openBatteryExemption(activity: Activity): Boolean {
        val packageUri = Uri.parse("package:${activity.packageName}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !isIgnoringBatteryOptimizations(activity)
        ) {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                packageUri
            )
            if (canResolve(activity, direct) && start(activity, direct)) {
                return true
            }

            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (canResolve(activity, list) && start(activity, list)) {
                return true
            }
        }

        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            packageUri
        )
        return canResolve(activity, appDetails) && start(activity, appDetails)
    }

    fun openAccessibilityWithGuide(activity: Activity): Boolean {
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        return openWithGuide(
            activity = activity,
            settingsIntent = settingsIntent,
            destination = SetupGuideActivity.Destination.ACCESSIBILITY
        )
    }

    fun isAutostartAvailable(context: Context): Boolean =
        resolveAutostartIntent(context) != null

    fun openAutostartWithGuide(activity: Activity): Boolean {
        val target = resolveAutostartIntent(activity) ?: return false
        return openWithGuide(
            activity = activity,
            settingsIntent = target,
            destination = SetupGuideActivity.Destination.AUTOSTART
        )
    }

    /**
     * Returns only a real, resolvable OEM Autostart/Auto-launch screen.
     * Generic app-details/battery screens are deliberately NOT considered an
     * Autostart destination: if none of the known OEM activities exists, the
     * Autostart row is hidden from the UI.
     */
    private fun resolveAutostartIntent(context: Context): Intent? {
        return autostartCandidates(context).firstOrNull { canResolve(context, it) }
    }

    private fun autostartCandidates(context: Context): List<Intent> {
        val device = buildString {
            append(Build.MANUFACTURER)
            append(' ')
            append(Build.BRAND)
        }.lowercase(Locale.ROOT)

        fun component(packageName: String, className: String): Intent =
            Intent().setComponent(ComponentName(packageName, className))

        return when {
            containsAny(device, "xiaomi", "redmi", "poco") -> listOf(
                component(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                Intent("miui.intent.action.OP_AUTO_START")
                    .setPackage("com.miui.securitycenter")
                    .putExtra("package_name", context.packageName)
            )

            containsAny(device, "huawei", "honor") -> listOf(
                component(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                component(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                ),
                component(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )

            containsAny(device, "oppo", "realme") -> listOf(
                component(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                component(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ),
                component(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            )

            containsAny(device, "vivo", "iqoo") -> listOf(
                component(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                component(
                    "com.iqoo.secure",
                    "com.iqoo.secure.safeguard.PurviewTabActivity"
                )
            )

            containsAny(device, "oneplus") -> listOf(
                component(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                ),
                component(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            )

            containsAny(device, "letv", "leeco") -> listOf(
                component(
                    "com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"
                ),
                Intent("com.letv.android.permissionautoboot")
            )

            containsAny(device, "tecno", "infinix", "itel") -> listOf(
                component(
                    "com.transsion.phonemaster",
                    "com.cyin.himgr.autostart.AutoStartActivity"
                )
            )

            else -> emptyList()
        }
    }

    private fun containsAny(value: String, vararg markers: String): Boolean =
        markers.any(value::contains)

    private fun openWithGuide(
        activity: Activity,
        settingsIntent: Intent,
        destination: SetupGuideActivity.Destination
    ): Boolean {
        if (!canResolve(activity, settingsIntent)) return false
        if (!start(activity, settingsIntent)) return false

        // Same visual sequence as the reference implementation:
        // Settings opens first, then our translucent Activity is placed on
        // top of it. Settings remains visible underneath the transparent
        // window; the guide panel appears after a short delay and then the
        // Activity finishes automatically.
        try {
            activity.startActivity(SetupGuideActivity.intent(activity, destination))
        } catch (_: Exception) {
            // The requested system settings screen is already open, so a
            // guide-rendering failure must not prevent the user continuing.
        }
        return true
    }

    private fun canResolve(context: Context, intent: Intent): Boolean = try {
        intent.resolveActivity(context.packageManager) != null
    } catch (_: Exception) {
        false
    }

    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
