package com.textgate.ai

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.textgate.ai.model.Languages
import com.textgate.ai.security.AppSettingsStore
import java.util.Locale

/**
 * Forces a specific interface [Locale] onto a [Context], independent of
 * whatever language the device itself is set to — the mechanism behind the
 * "App interface language" picker in Settings (see
 * [com.textgate.ai.security.AppSettingsStore.appInterfaceLanguage]).
 *
 * This app has zero third-party dependencies (see build.gradle.kts), so the
 * usual shortcut — AndroidX's `AppCompatDelegate.setApplicationLocales` —
 * is not available here; this is the equivalent hand-rolled with only
 * platform APIs, using the standard `attachBaseContext` +
 * `createConfigurationContext` pattern every locale-override app used
 * before that AndroidX helper existed.
 *
 * [TextGateApplication.attachBaseContext] applies this once for the whole
 * process (covering the AccessibilityService's own `getString`/toast calls,
 * since a Service's Context normally inherits the Application's
 * configuration when it has no override of its own), and
 * [com.textgate.ai.settings.SettingsActivity.attachBaseContext] applies it
 * again for that Activity specifically — Activities can be individually
 * re-created by the system with their own fresh Configuration (e.g. after a
 * device rotation or a system language change while the app is
 * backgrounded), so relying on the Application's override alone is not
 * reliable for Activities on every Android version; overriding both is the
 * standard, defensive approach.
 */
object LocaleHelper {

    /**
     * Wraps [base] with the user's chosen interface language, if they have
     * set one via [AppSettingsStore.appInterfaceLanguage]. Returns [base]
     * unchanged when that setting is null (the "follow the device's system
     * language" default) or when the stored code is no longer recognized.
     */
    fun applyOverride(base: Context): Context {
        val languageCode = AppSettingsStore(base).appInterfaceLanguage ?: return base
        return wrap(base, languageCode)
    }

    /**
     * Wraps [base] with the [java.util.Locale] for [languageCode] (one of
     * [Languages.ALL]'s codes). Returns [base] unchanged if [languageCode]
     * is not recognized, rather than crashing.
     */
    fun wrap(base: Context, languageCode: String): Context {
        val language = Languages.byCode(languageCode) ?: return base
        val locale = Locale.forLanguageTag(language.localeLanguageTag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))

        return base.createConfigurationContext(config)
    }
}
