package com.textgate.ai.security

import android.content.Context
import android.content.SharedPreferences
import com.textgate.ai.model.AudioCaptureMode
import com.textgate.ai.model.HeadsetDisconnectBehavior
import com.textgate.ai.model.Languages
import com.textgate.ai.model.UserGender

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

    // Falls back to `context` itself (instead of only `context.applicationContext`)
    // because this store is now also constructed from inside
    // Application.attachBaseContext (see LocaleHelper.applyOverride, called from
    // TextGateApplication) — at that exact point in the Android lifecycle,
    // getApplicationContext() has a well-known chicken-and-egg gap: the
    // Application object IS what becomes the application context, but it has
    // not finished attaching to itself yet, so applicationContext is still
    // null. getSharedPreferences() does not retain a reference to whichever
    // Context it was called through (unlike, say, storing the Context in a
    // field for later use, which is the actual leak-prone pattern this
    // app avoids elsewhere), so falling back to the raw Context here is safe.
    private val prefs: SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /**
     * Target language for the long-press "translate what's under my
     * finger" bubble (see [com.textgate.ai.security.BubbleTranslateGate]
     * and [com.textgate.ai.accessibility.TranslationBubble]) — deliberately
     * a SEPARATE setting from the typed `?xx` triggers, since this feature
     * has no per-use trigger to encode a language choice in: the user picks
     * one default once, here, in Settings.
     *
     * Stored as the raw [com.textgate.ai.model.SupportedLanguage.code]
     * string directly (e.g. "en", "de", "pt-rBR") rather than an enum-style
     * tag, since as of v1.4.0 there are 40 possible values, one per
     * [Languages.ALL] entry, and this way a language added to that list in
     * the future needs no matching change here. A value that is missing or
     * no longer recognized (e.g. a downgrade from a future version that
     * added more languages) falls back to [Languages.DEFAULT] rather than
     * crashing.
     */
    var bubbleTargetLanguage: TriggerDetector.Target
        get() {
            val storedCode = prefs.getString(KEY_BUBBLE_TARGET_LANGUAGE, null)
            val resolved = storedCode?.let { Languages.byCode(it) } ?: Languages.DEFAULT
            return TriggerDetector.Target(resolved.code)
        }
        set(value) {
            val resolvedCode = Languages.byCode(value.code)?.code ?: Languages.DEFAULT.code
            prefs.edit().putString(KEY_BUBBLE_TARGET_LANGUAGE, resolvedCode).apply()
        }

    /**
     * The app's own interface language — what every Activity and the
     * accessibility service's user-visible strings (toasts, notification
     * text) are shown in, applied via [com.textgate.ai.LocaleHelper]. Null
     * means "follow the device's system language" (this app's original,
     * pre-v1.4.0 behavior — it always just used whatever locale Android
     * picked); a non-null value is one of [Languages.ALL]'s codes and forces
     * that specific language regardless of the device's own setting.
     *
     * Deliberately separate from [bubbleTargetLanguage]: the user answered
     * "Jedno i drugie" (both) when asked whether choosing a language from
     * the bubble's list should change the translation target, the app's own
     * UI language, or both — so the bubble's picker updates both settings
     * together (see SettingsActivity), but they remain independently
     * readable/settable here since a user might later want the app's UI in
     * one language while still translating into another.
     */
    var appInterfaceLanguage: String?
        get() {
            val storedCode = prefs.getString(KEY_APP_INTERFACE_LANGUAGE, null) ?: return null
            return Languages.byCode(storedCode)?.code
        }
        set(value) {
            val resolvedCode = value?.let { Languages.byCode(it)?.code }
            if (resolvedCode == null) {
                prefs.edit().remove(KEY_APP_INTERFACE_LANGUAGE).apply()
            } else {
                prefs.edit().putString(KEY_APP_INTERFACE_LANGUAGE, resolvedCode).apply()
            }
        }

    /**
     * The app owner's own grammatical-gender preference — see
     * [UserGender]'s class doc for exactly what this does and does not
     * affect. Read ONLY by the typed-trigger translation path
     * ([com.textgate.ai.accessibility.TextGateAccessibilityService.confirmAndProcess]);
     * the long-press "translate a received message" bubble path must never
     * read it, since that text was written by someone else.
     */
    var userGender: UserGender
        get() = UserGender.fromPrefValue(prefs.getString(KEY_USER_GENDER, null))
        set(value) = prefs.edit().putString(KEY_USER_GENDER, value.prefValue).apply()

    /**
     * What an active "Na żywo" Live session does the instant the user's
     * headset disconnects — see [HeadsetDisconnectBehavior]'s own class doc
     * for the full behavior and the privacy reasoning behind its default.
     * Read only by [com.textgate.ai.live.LiveTranslationService].
     */
    var headsetDisconnectBehavior: HeadsetDisconnectBehavior
        get() = HeadsetDisconnectBehavior.fromPrefValue(prefs.getString(KEY_HEADSET_DISCONNECT_BEHAVIOR, null))
        set(value) = prefs.edit().putString(KEY_HEADSET_DISCONNECT_BEHAVIOR, value.prefValue).apply()

    /**
     * How Rozmowa and Na żywo capture and play audio — see
     * [AudioCaptureMode]'s own class doc for the full story: this is a
     * user-facing choice specifically because the two options trade off two
     * real, conflicting on-device reports (a speaker echo loop vs. lag and
     * cut-off turns) that this project cannot detect or resolve
     * automatically per-device. Read by
     * [com.textgate.ai.live.LiveTranslationService] and
     * [com.textgate.ai.conversation.ConversationTabController] at the start
     * of each capture/playback session — a mid-session change does not
     * retroactively affect an already-running session, only the next one.
     */
    var audioCaptureMode: AudioCaptureMode
        get() = AudioCaptureMode.fromPrefValue(prefs.getString(KEY_AUDIO_CAPTURE_MODE, null))
        set(value) = prefs.edit().putString(KEY_AUDIO_CAPTURE_MODE, value.prefValue).apply()

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
        private const val KEY_BUBBLE_TARGET_LANGUAGE = "bubble_target_language"
        private const val KEY_APP_INTERFACE_LANGUAGE = "app_interface_language"
        private const val KEY_USER_GENDER = "user_gender"
        private const val KEY_HEADSET_DISCONNECT_BEHAVIOR = "headset_disconnect_behavior"
        private const val KEY_AUDIO_CAPTURE_MODE = "audio_capture_mode"

        /**
         * Chosen from the app owner's own Google AI Studio rate-limit
         * dashboard: on that account, gemini-3.5-flash-lite carries by far
         * the highest free-tier quota of any text-out model on offer (500
         * requests/day, 15 requests/minute — roughly 25x the 20/day,
         * 5-10/minute the other Flash-tier models get), while
         * gemini-2.5-pro showed a flat 0/0 quota. Real-world quotas are
         * account-specific and can change on Google's side without notice;
         * if this default ever starts failing, check
         * aistudio.google.com's rate-limit dashboard and update this
         * constant (or just type a different model into Settings — nothing
         * else in the app depends on which model is picked here).
         */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

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
