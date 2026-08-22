package com.textgate.ai

import android.app.Application
import android.content.Context

/**
 * Intentionally minimal. No analytics, crash-reporting, or telemetry SDK is
 * initialized here or anywhere else in this app — there simply isn't one
 * to initialize. See README.md "Dependency report" and "Network audit".
 *
 * [attachBaseContext] is overridden only to apply the user's chosen "App
 * interface language" (see [LocaleHelper] and
 * [com.textgate.ai.security.AppSettingsStore.appInterfaceLanguage]) to the
 * whole process's base Configuration, as of v1.4.0 — every other component
 * that has no override of its own (notably
 * [com.textgate.ai.accessibility.TextGateAccessibilityService], whose
 * toast/notification text uses [Context.getString]) inherits it from here.
 */
class TextGateApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(base))
    }
}
