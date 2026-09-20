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
import android.widget.ImageView
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R

/**
 * Translucent, short-lived help shown immediately after Google AI Studio is
 * opened in the browser. All user-facing text comes from the app's existing
 * localized resources, so the guide follows the language selected in TextGate
 * AI instead of hard-coding English or Polish.
 *
 * The three animated mini-screens are decorative only: they demonstrate
 * "create key -> confirm -> copy" inside this Activity and never interact with
 * the browser underneath.
 */
class ApiKeyGuideActivity : Activity() {

    companion object {
        private const val SHOW_DELAY_MS = 500L
        private const val AUTO_CLOSE_MS = 15_000L
        private const val STEP_DURATION_MS = 2_250L
        private const val TAP_START_DELAY_MS = 180L

        internal fun intent(context: Context): Intent =
            Intent(context, ApiKeyGuideActivity::class.java)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = 0

    private val showGuide = Runnable {
        findViewById<View>(R.id.apiKeyGuidePanel).visibility = View.VISIBLE
        currentStep = 0
        showCurrentStep()
    }

    private val autoClose = Runnable { finish() }

    private val animateCurrentStep = Runnable {
        val target = when (currentStep) {
            0 -> findViewById<View>(R.id.apiGuideActionCreate)
            1 -> findViewById<View>(R.id.apiGuideActionConfirm)
            else -> findViewById<View>(R.id.apiGuideActionCopy)
        }
        animateTap(target, currentStep == 2)
    }

    private val advanceStep = Runnable {
        currentStep = (currentStep + 1) % 3
        showCurrentStep()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_key_guide)
        overridePendingTransition(0, 0)

        findViewById<View>(R.id.buttonCloseApiKeyGuide).setOnClickListener { finish() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        handler.postDelayed(showGuide, SHOW_DELAY_MS)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private fun showCurrentStep() {
        handler.removeCallbacks(animateCurrentStep)
        handler.removeCallbacks(advanceStep)

        val stageCreate = findViewById<View>(R.id.apiGuideStageCreate)
        val stageConfirm = findViewById<View>(R.id.apiGuideStageConfirm)
        val stageCopy = findViewById<View>(R.id.apiGuideStageCopy)
        val hand = findViewById<ImageView>(R.id.apiGuideTouchHand)
        val copyIcon = findViewById<ImageView>(R.id.apiGuideCopyIcon)

        stageCreate.visibility = if (currentStep == 0) View.VISIBLE else View.GONE
        stageConfirm.visibility = if (currentStep == 1) View.VISIBLE else View.GONE
        stageCopy.visibility = if (currentStep == 2) View.VISIBLE else View.GONE

        hand.animate().cancel()
        hand.alpha = 0f
        hand.scaleX = 1f
        hand.scaleY = 1f
        copyIcon.setImageResource(R.drawable.ic_api_key_guide_copy)

        handler.postDelayed(animateCurrentStep, TAP_START_DELAY_MS)
        handler.postDelayed(advanceStep, STEP_DURATION_MS)
    }

    private fun animateTap(target: View, isCopyStep: Boolean) {
        val demo = findViewById<View>(R.id.apiKeyGuideDemo)
        val hand = findViewById<ImageView>(R.id.apiGuideTouchHand)
        val copyIcon = findViewById<ImageView>(R.id.apiGuideCopyIcon)

        demo.post {
            if (isFinishing || isDestroyed || target.visibility != View.VISIBLE) return@post

            target.animate().cancel()
            target.scaleX = 1f
            target.scaleY = 1f

            hand.animate().cancel()
            hand.alpha = 0f
            hand.scaleX = 1f
            hand.scaleY = 1f
            hand.translationX = 0f
            hand.translationY = 0f

            // Use screen coordinates because the three tap targets live at
            // different nesting depths inside the demo card.
            val targetLocation = IntArray(2)
            val handLocation = IntArray(2)
            target.getLocationOnScreen(targetLocation)
            hand.getLocationOnScreen(handLocation)

            val targetCenterX = targetLocation[0] + target.width / 2f
            val targetCenterY = targetLocation[1] + target.height / 2f
            val handCenterX = handLocation[0] + hand.width / 2f
            val handCenterY = handLocation[1] + hand.height / 2f

            val destinationX = targetCenterX - handCenterX
            val destinationY = targetCenterY - handCenterY + dp(9)

            hand.translationX = destinationX + dp(26)
            hand.translationY = destinationY + dp(8)

            hand.animate()
                .alpha(1f)
                .translationX(destinationX)
                .translationY(destinationY)
                .setDuration(360L)
                .withEndAction {
                    if (isFinishing || isDestroyed) return@withEndAction

                    hand.animate()
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(110L)
                        .start()

                    target.animate()
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(110L)
                        .withEndAction {
                            target.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(120L)
                                .start()

                            hand.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(120L)
                                .start()

                            if (isCopyStep) {
                                copyIcon.setImageResource(R.drawable.ic_api_key_guide_check)
                            }

                            hand.animate()
                                .alpha(0f)
                                .translationY(destinationY + dp(5))
                                .setStartDelay(260L)
                                .setDuration(180L)
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    private fun dp(value: Int): Float =
        value * resources.displayMetrics.density

    override fun onDestroy() {
        handler.removeCallbacks(showGuide)
        handler.removeCallbacks(autoClose)
        handler.removeCallbacks(animateCurrentStep)
        handler.removeCallbacks(advanceStep)
        findViewById<View>(R.id.apiGuideTouchHand).animate().cancel()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
