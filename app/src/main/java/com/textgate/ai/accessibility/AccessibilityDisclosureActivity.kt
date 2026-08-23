package com.textgate.ai.accessibility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R
import com.textgate.ai.databinding.ActivityAccessibilityDisclosureBinding

/**
 * Google Play's "prominent disclosure and consent" screen for apps that use
 * [android.accessibilityservice.AccessibilityService] as a non-accessibility
 * tool (see docs/publikacja_google_play.md, section 0, and
 * https://support.google.com/googleplay/android-developer/answer/10964491).
 *
 * The policy requires a screen that:
 *   1. Is part of the app itself, not the Android system Settings screen.
 *   2. Is shown BEFORE the user is sent to enable the service.
 *   3. Clearly discloses what is read and why.
 *   4. Requires an explicit affirmative action — a button that reads like
 *      real consent ("I agree"), not a dismissive "OK" / "Understood" — to
 *      proceed.
 *
 * [com.textgate.ai.settings.SettingsActivity] launches this Activity
 * instead of jumping straight to [Settings.ACTION_ACCESSIBILITY_SETTINGS];
 * only [buttonAgree] does that, and only after the user has read
 * [R.string.accessibility_disclosure_body] on this screen. Tapping "Cancel"
 * (or the system back gesture) simply returns to Settings with nothing
 * changed — the service is not enabled until the user separately switches
 * it on in the system screen this leads to.
 */
class AccessibilityDisclosureActivity : Activity() {

    private lateinit var binding: ActivityAccessibilityDisclosureBinding

    /** Same per-Activity locale re-application as every other screen in
     * this app — see [com.textgate.ai.settings.SettingsActivity.attachBaseContext]
     * for why this can't rely solely on the Application-level override. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessibilityDisclosureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonAgree.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
            // Whether or not the system screen could be opened, this
            // disclosure screen's job is done — the user has already made
            // their affirmative choice. Returning to SettingsActivity lets
            // its onResume() refresh the enabled/disabled status label as
            // usual once the user comes back from system Settings.
            finish()
        }

        binding.buttonCancel.setOnClickListener {
            finish()
        }
    }
}
