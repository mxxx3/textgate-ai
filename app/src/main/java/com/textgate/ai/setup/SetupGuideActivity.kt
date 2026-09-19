package com.textgate.ai.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R

class SetupGuideActivity : Activity() {
    companion object {
        private const val EXTRA_DESTINATION = "destination"
        private const val STATE_AWAITING_PERMISSION = "awaiting_permission"

        internal fun intent(context: Context, destination: SetupGuideOverlay.Destination): Intent =
            Intent(context, SetupGuideActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.name)
    }

    private var awaitingPermission = false
    private var openingDestination = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = destination()
        if (target == null) {
            finish()
            return
        }
        awaitingPermission = savedInstanceState?.getBoolean(STATE_AWAITING_PERMISSION) == true
        if (Settings.canDrawOverlays(this)) {
            openDestination()
            return
        }

        setContentView(R.layout.activity_setup_guide)
        findViewById<TextView>(R.id.textTargetSteps).setText(target.stepsRes)
        findViewById<android.view.View>(R.id.buttonEnableGuide).setOnClickListener {
            awaitingPermission = true
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                awaitingPermission = false
                openDestination()
            }
        }
        findViewById<android.view.View>(R.id.buttonSkipGuide).setOnClickListener {
            openDestination()
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission) {
            awaitingPermission = false
            openDestination()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_PERMISSION, awaitingPermission)
        super.onSaveInstanceState(outState)
    }

    private fun destination(): SetupGuideOverlay.Destination? =
        SetupGuideOverlay.Destination.entries.firstOrNull {
            it.name == intent.getStringExtra(EXTRA_DESTINATION)
        }

    private fun openDestination() {
        if (openingDestination) return
        openingDestination = true
        val target = destination() ?: return finish()
        if (Settings.canDrawOverlays(this)) SetupGuideOverlay.show(this, target)

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
        val opened = intents.any { setting ->
            try {
                startActivity(setting)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!opened) {
            SetupGuideOverlay.dismiss()
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
