package com.textgate.ai.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R

class SetupGuideActivity : Activity() {
    companion object {
        private const val TAG = "SetupGuideActivity"
        private const val EXTRA_DESTINATION = "destination"
        private const val STATE_EXTERNAL_SCREEN = "external_screen"
        private const val STATE_LEFT_FOR_EXTERNAL_SCREEN = "left_for_external_screen"

        internal fun intent(context: Context, destination: SetupGuideOverlay.Destination): Intent =
            Intent(context, SetupGuideActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.name)
    }

    private enum class ExternalScreen { NONE, OVERLAY_PERMISSION, DESTINATION }

    private var externalScreen = ExternalScreen.NONE
    private var leftForExternalScreen = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = destination()
        if (target == null) {
            Log.e(TAG, "Missing or invalid destination")
            finish()
            return
        }

        setContentView(R.layout.activity_setup_guide)
        findViewById<TextView>(R.id.textTargetSteps).setText(target.stepsRes)
        findViewById<View>(R.id.buttonEnableGuide).setOnClickListener {
            if (Settings.canDrawOverlays(this)) openDestination(withGuide = true)
            else requestOverlayPermission()
        }
        findViewById<View>(R.id.buttonSkipGuide).setOnClickListener {
            openDestination(withGuide = false)
        }

        if (savedInstanceState != null) {
            val savedScreen = savedInstanceState.getString(STATE_EXTERNAL_SCREEN)
            externalScreen = ExternalScreen.entries.firstOrNull { it.name == savedScreen }
                ?: ExternalScreen.NONE
            leftForExternalScreen = savedInstanceState.getBoolean(STATE_LEFT_FOR_EXTERNAL_SCREEN)
        } else if (Settings.canDrawOverlays(this)) {
            openDestination(withGuide = true)
        }
    }

    override fun onPause() {
        super.onPause()
        if (externalScreen != ExternalScreen.NONE) {
            leftForExternalScreen = true
            Log.d(TAG, "Paused for $externalScreen")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume externalScreen=$externalScreen leftForExternalScreen=$leftForExternalScreen")
        if (!leftForExternalScreen) return

        val returnedFrom = externalScreen
        externalScreen = ExternalScreen.NONE
        leftForExternalScreen = false
        Log.d(TAG, "Returned from $returnedFrom; canDrawOverlays=${Settings.canDrawOverlays(this)}")
        when (returnedFrom) {
            ExternalScreen.OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) openDestination(withGuide = true)
                else showStatus(R.string.setup_guide_permission_missing)
            }
            ExternalScreen.DESTINATION -> {
                SetupGuideOverlay.dismiss("returned from Settings")
                finish()
            }
            ExternalScreen.NONE -> Unit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_EXTERNAL_SCREEN, externalScreen.name)
        outState.putBoolean(STATE_LEFT_FOR_EXTERNAL_SCREEN, leftForExternalScreen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy finishing=$isFinishing externalScreen=$externalScreen")
        super.onDestroy()
    }

    private fun destination(): SetupGuideOverlay.Destination? =
        SetupGuideOverlay.Destination.entries.firstOrNull {
            it.name == intent.getStringExtra(EXTRA_DESTINATION)
        }

    private fun requestOverlayPermission() {
        externalScreen = ExternalScreen.OVERLAY_PERMISSION
        leftForExternalScreen = false
        Log.d(TAG, "Opening overlay permission Settings")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open overlay permission Settings", e)
            externalScreen = ExternalScreen.NONE
            showStatus(R.string.setup_guide_settings_failed)
        }
    }

    private fun openDestination(withGuide: Boolean) {
        if (externalScreen != ExternalScreen.NONE) return
        val target = destination() ?: return
        val permitted = Settings.canDrawOverlays(this)
        Log.d(TAG, "Opening $target; withGuide=$withGuide; canDrawOverlays=$permitted")
        if (withGuide && (!permitted || !SetupGuideOverlay.show(this, target))) {
            showStatus(if (permitted) R.string.setup_guide_overlay_failed else R.string.setup_guide_permission_missing)
            return
        }

        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        val intents = when (target) {
            SetupGuideOverlay.Destination.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupGuideOverlay.Destination.BATTERY -> listOf(appDetails)
            SetupGuideOverlay.Destination.AUTOSTART -> listOf(
                Intent().setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                Intent("miui.intent.action.OP_AUTO_START"),
                appDetails
            )
        }

        externalScreen = ExternalScreen.DESTINATION
        leftForExternalScreen = false
        val opened = intents.any { setting ->
            try {
                Log.d(TAG, "Starting Settings action=${setting.action} component=${setting.component}")
                startActivity(setting)
                Log.d(TAG, "Settings launch succeeded")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Settings launch failed", e)
                false
            }
        }
        if (!opened) {
            externalScreen = ExternalScreen.NONE
            SetupGuideOverlay.dismiss("Settings launch failed")
            showStatus(R.string.setup_guide_settings_failed)
        }
    }

    private fun showStatus(messageRes: Int) {
        findViewById<TextView>(R.id.textGuideStatus).apply {
            setText(messageRes)
            visibility = View.VISIBLE
        }
    }
}
