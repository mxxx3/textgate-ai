package com.textgate.ai.setup

import android.app.Activity

/**
 * Shows the localized, animated API-key instructions inside TextGate AI.
 * The browser is opened only after the user taps the guide's existing
 * "Get a free API key" action, so the instructions are guaranteed to be
 * visible before leaving the app.
 */
internal object ApiKeyGuideLauncher {

    fun open(activity: Activity): Boolean = try {
        activity.startActivity(ApiKeyGuideActivity.intent(activity))
        true
    } catch (_: Exception) {
        false
    }
}
