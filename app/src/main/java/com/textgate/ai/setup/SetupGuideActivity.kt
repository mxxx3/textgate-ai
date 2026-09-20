package com.textgate.ai.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R

/**
 * Short-lived translucent instruction sheet shown on top of a system/OEM
 * Settings screen. This deliberately does NOT use SYSTEM_ALERT_WINDOW.
 *
 * Sequence:
 * 1. the caller opens the target Settings Activity;
 * 2. the caller immediately starts this Activity;
 * 3. the transparent window leaves Settings visible underneath;
 * 4. after 500 ms the bottom guide becomes visible;
 * 5. after 8 seconds (or Close) this Activity finishes and Settings remains.
 */
class SetupGuideActivity : Activity() {

    enum class Destination(val stepsRes: Int) {
        ACCESSIBILITY(R.string.setup_guide_accessibility_steps),
        AUTOSTART(R.string.setup_guide_autostart_steps)
    }

    companion object {
        private const val EXTRA_DESTINATION = "destination"
        private const val SHOW_DELAY_MS = 500L
        private const val AUTO_CLOSE_MS = 8_000L

        internal fun intent(context: Context, destination: Destination): Intent =
            Intent(context, SetupGuideActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.name)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val showGuide = Runnable {
        findViewById<View?>(R.id.lineSetupGuide)?.visibility = View.VISIBLE
    }
    private val autoClose = Runnable { finish() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destination = Destination.entries.firstOrNull {
            it.name == intent.getStringExtra(EXTRA_DESTINATION)
        } ?: run {
            finish()
            return
        }

        setContentView(R.layout.activity_setup_guide)
        overridePendingTransition(0, 0)

        findViewById<TextView>(R.id.textGuideSteps).setText(destination.stepsRes)
        findViewById<View>(R.id.buttonCloseGuide).setOnClickListener { finish() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.navigationBars())
                systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        handler.postDelayed(showGuide, SHOW_DELAY_MS)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(showGuide)
        handler.removeCallbacks(autoClose)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
