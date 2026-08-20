package com.textgate.ai.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret configuration: the master on/off switch, the user's allow-list
 * of package names, and the chosen Gemini model name. None of these values
 * are credentials, so they are kept in a plain (not Keystore-encrypted)
 * SharedPreferences file — but that file is still covered by
 * android:allowBackup="false" / data_extraction_rules.xml like everything
 * else this app writes, and is created with MODE_PRIVATE (default), so it
 * is not readable by other apps.
 *
 * Every default here is chosen to be the SAFEST possible starting state:
 *   - AI transformation starts DISABLED.
 *   - The allow-list starts EMPTY — no app is monitored until the user adds it.
 */
class AppSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    fun getAllowedPackages(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun isPackageAllowed(packageName: String): Boolean =
        getAllowedPackages().contains(packageName)

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        // getStringSet's returned Set must be treated as immutable per the
        // SharedPreferences contract; copy before mutating.
        val updated = getAllowedPackages().toMutableSet()
        if (allowed) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, updated).apply()
    }

    companion object {
        private const val PREFS_NAME = "textgate_settings"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
