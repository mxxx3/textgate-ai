package com.textgate.ai

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
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

    /**
     * Resolves [AppSettingsStore.appInterfaceLanguage] down to one concrete
     * [SupportedLanguage] — used by the typed-trigger translation prompt
     * (see [com.textgate.ai.model.TranslationPrompts.systemPromptFor]'s
     * `userPreferredLanguage` parameter, added in v1.7.1) so a stored
     * `null` ("follow the device's system language") still yields one real
     * language to reason about, rather than the prompt needing to handle
     * "unknown". A stored code is already unambiguous and is returned
     * directly; `null` is resolved from [context]'s own current
     * [Configuration] locale — the exact same signal Android itself
     * already used to pick this very [context]'s `values-<code>/
     * strings.xml` — so this reuses information the platform already
     * computed instead of guessing independently or making any network
     * call.
     */
    fun resolvePreferredLanguage(context: Context): SupportedLanguage {
        val storedCode = AppSettingsStore(context).appInterfaceLanguage
        if (storedCode != null) {
            return Languages.byCode(storedCode) ?: Languages.DEFAULT
        }

        val locales = context.resources.configuration.locales
        val systemLocale = if (!locales.isEmpty()) locales[0] else Locale.getDefault()
        return matchToSupportedLanguage(systemLocale)
    }

    /**
     * Best-effort match of an arbitrary device [Locale] to one entry in
     * [Languages.ALL]. Tries an exact language+country match first — so
     * e.g. a device set to "pt-BR" resolves to Brazilian Portuguese and
     * "zh-CN" to Simplified Chinese, not their generic same-language
     * sibling — then falls back to the first [Languages.ALL] entry sharing
     * just the bare language subtag (list order breaks the tie, same as
     * every other "closest match" fallback in this app), then finally to
     * [Languages.DEFAULT] if nothing shares even that much — the same
     * "never crash, fall back to a sane default" shape [Languages.byCode]
     * already uses for an unrecognized stored code.
     */
    private fun matchToSupportedLanguage(locale: Locale): SupportedLanguage {
        val exact = Languages.ALL.firstOrNull { candidate ->
            val candidateLocale = Locale.forLanguageTag(candidate.localeLanguageTag)
            candidateLocale.language == locale.language && candidateLocale.country == locale.country
        }
        if (exact != null) return exact

        val byLanguageOnly = Languages.ALL.firstOrNull { candidate ->
            Locale.forLanguageTag(candidate.localeLanguageTag).language == locale.language
        }
        return byLanguageOnly ?: Languages.DEFAULT
    }
}
