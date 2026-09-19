package com.textgate.ai.accessibility

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R
import com.textgate.ai.databinding.ActivityAccessibilityDisclosureBinding
import com.textgate.ai.setup.SetupGuideActivity
import com.textgate.ai.setup.SetupGuideOverlay

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
 * before any system Settings screen; only [buttonAgree] proceeds to the
 * optional floating-guide choice and then Accessibility settings. Tapping "Cancel"
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
                startActivity(SetupGuideActivity.intent(this, SetupGuideOverlay.Destination.ACCESSIBILITY))
                finish()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonCancel.setOnClickListener {
            finish()
        }
    }
}
