package com.textgate.ai.setup

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.textgate.ai.accessibility.TextGateAccessibilityService
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore

internal object SetupReadiness {
    private const val PREFS_NAME = "setup_checklist"
    private const val BATTERY_CHOICE = "battery_choice"
    private const val AUTOSTART_CHOICE = "autostart_choice"
    private const val TESTED_KEY_AND_MODEL = "tested_key_and_model"

    enum class ManualChoice { PENDING, ENABLED, UNAVAILABLE }

    data class Status(
        val apiKeyReady: Boolean,
        val apiTestPassed: Boolean,
        val accessibilityEnabled: Boolean,
        val triggerEnabled: Boolean,
        val batteryChoice: ManualChoice,
        val autostartChoice: ManualChoice
    ) {
        val ready: Boolean
            get() = apiKeyReady && apiTestPassed && accessibilityEnabled && triggerEnabled &&
                batteryChoice != ManualChoice.PENDING && autostartChoice != ManualChoice.PENDING
    }

    fun status(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyStore = SecureApiKeyStore(context)
        val settingsStore = AppSettingsStore(context)
        val currentTest = testSignature(keyStore.activeKeyId(), settingsStore.selectedModel)
        return Status(
            apiKeyReady = keyStore.getActiveKeyPlaintext() != null,
            apiTestPassed = currentTest != null && prefs.getString(TESTED_KEY_AND_MODEL, null) == currentTest,
            accessibilityEnabled = isAccessibilityEnabled(context),
            triggerEnabled = settingsStore.isAiEnabled,
            batteryChoice = readChoice(prefs.getString(BATTERY_CHOICE, null)),
            autostartChoice = readChoice(prefs.getString(AUTOSTART_CHOICE, null))
        )
    }

    fun chooseBattery(context: Context, choice: ManualChoice) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(BATTERY_CHOICE, choice.name).apply()

    fun chooseAutostart(context: Context, choice: ManualChoice) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(AUTOSTART_CHOICE, choice.name).apply()

    private fun readChoice(value: String?): ManualChoice =
        ManualChoice.entries.firstOrNull { it.name == value } ?: ManualChoice.PENDING

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
