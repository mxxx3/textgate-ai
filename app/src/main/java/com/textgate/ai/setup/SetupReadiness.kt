package com.textgate.ai.setup

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.textgate.ai.accessibility.TextGateAccessibilityService
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore

internal object SetupReadiness {
    private const val PREFS_NAME = "setup_checklist"
    private const val BATTERY_CONFIRMED = "battery_confirmed"
    private const val AUTOSTART_CONFIRMED = "autostart_confirmed"
    private const val TESTED_KEY_AND_MODEL = "tested_key_and_model"

    data class Status(
        val apiKeyReady: Boolean,
        val apiTestPassed: Boolean,
        val accessibilityEnabled: Boolean,
        val triggerEnabled: Boolean,
        val needsOemSteps: Boolean,
        val batteryConfirmed: Boolean,
        val autostartConfirmed: Boolean
    ) {
        val ready: Boolean
            get() = apiKeyReady && apiTestPassed && accessibilityEnabled && triggerEnabled &&
                (!needsOemSteps || (batteryConfirmed && autostartConfirmed))
    }

    fun status(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val needsOemSteps = listOf("xiaomi", "redmi", "poco").any { maker.contains(it) }
        val keyStore = SecureApiKeyStore(context)
        val settingsStore = AppSettingsStore(context)
        val currentTest = testSignature(keyStore.activeKeyId(), settingsStore.selectedModel)
        return Status(
            apiKeyReady = keyStore.getActiveKeyPlaintext() != null,
            apiTestPassed = currentTest != null && prefs.getString(TESTED_KEY_AND_MODEL, null) == currentTest,
            accessibilityEnabled = isAccessibilityEnabled(context),
            triggerEnabled = settingsStore.isAiEnabled,
            needsOemSteps = needsOemSteps,
            batteryConfirmed = prefs.getBoolean(BATTERY_CONFIRMED, false),
            autostartConfirmed = prefs.getBoolean(AUTOSTART_CONFIRMED, false)
        )
    }

    fun confirmBattery(context: Context, confirmed: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(BATTERY_CONFIRMED, confirmed).apply()

    fun confirmAutostart(context: Context, confirmed: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTOSTART_CONFIRMED, confirmed).apply()

    fun recordApiTest(context: Context, passed: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val test = if (passed) {
            testSignature(SecureApiKeyStore(context).activeKeyId(), AppSettingsStore(context).selectedModel)
        } else null
        val editor = prefs.edit()
        if (test == null) editor.remove(TESTED_KEY_AND_MODEL) else editor.putString(TESTED_KEY_AND_MODEL, test)
        editor.apply()
    }

    private fun testSignature(keyId: String?, model: String): String? =
        keyId?.let { "$it:$model" }

    private fun isAccessibilityEnabled(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo?.serviceInfo?.let { service ->
                    service.packageName == context.packageName &&
                        service.name == TextGateAccessibilityService::class.java.name
                } == true
            }
    } catch (_: Exception) {
        false
    }
}
