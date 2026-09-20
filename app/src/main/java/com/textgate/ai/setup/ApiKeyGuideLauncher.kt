package com.textgate.ai.setup

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * Opens Google AI Studio and immediately places our translucent, localized
 * help Activity on top. When the help closes, the browser remains visible
 * underneath at the API-key page.
 */
internal object ApiKeyGuideLauncher {
    private const val GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey"

    fun open(activity: Activity): Boolean {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_API_KEY_URL))
        val canOpen = try {
            browserIntent.resolveActivity(activity.packageManager) != null
        } catch (_: Exception) {
            false
        }
        if (!canOpen) return false

        try {
            activity.startActivity(browserIntent)
        } catch (_: Exception) {
            return false
        }

        try {
            activity.startActivity(ApiKeyGuideActivity.intent(activity))
        } catch (_: Exception) {
            // The browser is already open. A guide-rendering failure should
            // never prevent the user from creating a key manually.
        }
        return true
    }
}
