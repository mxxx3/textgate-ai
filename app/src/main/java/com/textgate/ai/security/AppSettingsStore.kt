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
 * Defaults:
 *   - AI transformation starts DISABLED — the user must turn it on.
 *   - The allow-list starts pre-populated with [DEFAULT_ALLOWED_PACKAGES],
 *     a curated, best-effort set of common social-media and messaging
 *     apps, so day-to-day use needs no manual per-app setup. This is a
 *     deliberate, requested trade-off from the original "start empty"
 *     policy — convenience only, not a weaker security boundary: every
 *     package here (default or user-added) is still filtered through
 *     [AppBlocklist] by EventGate before anything is ever read, so a
 *     password manager, banking app, authenticator, or crypto wallet stays
 *     blocked even if it were somehow present in this set.
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

    /**
     * The allow-list as it stands: the user's own explicit choices once
     * they have made any (even down to an empty set, e.g. after removing
     * every default), or else [DEFAULT_ALLOWED_PACKAGES] as long as the
     * user has never touched this setting at all.
     */
    fun getAllowedPackages(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, null)?.toSet() ?: DEFAULT_ALLOWED_PACKAGES

    fun isPackageAllowed(packageName: String): Boolean =
        getAllowedPackages().contains(packageName)

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        // getStringSet's returned Set must be treated as immutable per the
        // SharedPreferences contract; copy before mutating. The very first
        // call here "materializes" the previously-implicit default set
        // into prefs, which is intentional: from this point on the user's
        // own choices are the whole story, not a blend with the curated
        // default.
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

        /**
         * Curated, best-effort package names for common social-media and
         * messaging apps. Not exhaustive by design — the "Allowed apps"
         * advanced picker in Settings still lists every launchable app on
         * the device so anything missing here can be added by hand. As
         * with any package-name list in this app (see AppBlocklist), these
         * are ordinary strings with no special privilege: they are only
         * ever consulted through [isPackageAllowed], which every read path
         * also runs through [AppBlocklist] first.
         */
        val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(
            // Messaging
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",
            "org.thoughtcrime.securesms",
            "com.viber.voip",
            "com.discord",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.skype.raider",
            "com.tencent.mm",
            "jp.naver.line.android",
            "com.kakao.talk",
            // Social media
            "com.instagram.android",
            "com.facebook.katana",
            "com.instagram.barcelona",
            "com.twitter.android",
            "com.snapchat.android",
            "com.zhiliaoapp.musically",
            "com.linkedin.android",
            "com.reddit.frontpage",
            "com.pinterest"
        )
    }
}
