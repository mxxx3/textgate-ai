package com.textgate.ai.setup

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
 * 5. a decorative hand animation demonstrates sliding the switch;
 * 6. after 8 seconds (or Close) this Activity finishes and Settings remains.
 *
 * The switch shown inside this guide is intentionally only a visual example.
 * It never clicks, touches, or changes any control in Android Settings.
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
        private const val DEMO_RESTART_DELAY_MS = 700L

        internal fun intent(context: Context, destination: Destination): Intent =
            Intent(context, SetupGuideActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.name)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var demoAnimator: AnimatorSet? = null

    private val restartDemo = Runnable {
        if (!isFinishing && !isDestroyed) startSwitchDemo()
    }

    private val showGuide = Runnable {
        findViewById<View>(R.id.lineSetupGuide).visibility = View.VISIBLE
        startSwitchDemo()
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

    /**
     * Demonstrates the same action the user should perform in Settings:
     * a hand moves onto the example switch, presses it, and slides the thumb
     * from left to right. The sequence is looped while this short guide is
     * visible. It is entirely inside our Activity and has no interaction with
     * the real Settings UI underneath.
     */
    private fun startSwitchDemo() {
        val track = findViewById<View>(R.id.guideFakeSwitch)
        val thumb = findViewById<View>(R.id.guideFakeSwitchThumb)
        val hand = findViewById<ImageView>(R.id.guideTouchHand)

        track.post {
            if (isFinishing || isDestroyed) return@post

            handler.removeCallbacks(restartDemo)
            demoAnimator?.removeAllListeners()
            demoAnimator?.cancel()

            val travel = (
                track.width -
                    track.paddingLeft -
                    track.paddingRight -
                    thumb.width
                ).coerceAtLeast(0).toFloat()

            track.setBackgroundResource(R.drawable.bg_setup_guide_switch_off)
            thumb.translationX = 0f

            hand.alpha = 0f
            hand.translationX = -dp(28).toFloat()
            hand.translationY = dp(7).toFloat()
            hand.scaleX = 1f
            hand.scaleY = 1f

            val handFadeIn = ObjectAnimator.ofFloat(hand, View.ALPHA, 0f, 1f).apply {
                duration = 180L
            }
            val handApproachX = ObjectAnimator.ofFloat(
                hand,
                View.TRANSLATION_X,
                -dp(28).toFloat(),
                -dp(18).toFloat()
            ).apply {
                duration = 420L
            }
            val handApproachY = ObjectAnimator.ofFloat(
                hand,
                View.TRANSLATION_Y,
                dp(7).toFloat(),
                0f
            ).apply {
                duration = 420L
            }

            val pressX = ObjectAnimator.ofFloat(hand, View.SCALE_X, 1f, 0.9f).apply {
                duration = 120L
            }
            val pressY = ObjectAnimator.ofFloat(hand, View.SCALE_Y, 1f, 0.9f).apply {
                duration = 120L
            }

            val thumbSlide = ObjectAnimator.ofFloat(
                thumb,
                View.TRANSLATION_X,
                0f,
                travel
            ).apply {
                duration = 360L
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        track.setBackgroundResource(R.drawable.bg_setup_guide_switch_on)
                    }
                })
            }
            val handSlide = ObjectAnimator.ofFloat(
                hand,
                View.TRANSLATION_X,
                -dp(18).toFloat(),
                dp(3).toFloat()
            ).apply {
                duration = 360L
            }

            val releaseX = ObjectAnimator.ofFloat(hand, View.SCALE_X, 0.9f, 1f).apply {
                duration = 120L
            }
            val releaseY = ObjectAnimator.ofFloat(hand, View.SCALE_Y, 0.9f, 1f).apply {
                duration = 120L
            }
            val handFadeOut = ObjectAnimator.ofFloat(hand, View.ALPHA, 1f, 0f).apply {
                duration = 220L
                startDelay = 300L
            }

            demoAnimator = AnimatorSet().apply {
                play(handFadeIn).with(handApproachX).with(handApproachY)
                play(pressX).with(pressY).after(handApproachX)
                play(thumbSlide).with(handSlide).after(pressX)
                play(releaseX).with(releaseY).after(thumbSlide)
                play(handFadeOut).after(releaseX)

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isFinishing && !isDestroyed) {
                            handler.postDelayed(restartDemo, DEMO_RESTART_DELAY_MS)
                        }
                    }
                })
                start()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(showGuide)
        handler.removeCallbacks(autoClose)
        handler.removeCallbacks(restartDemo)
        demoAnimator?.removeAllListeners()
        demoAnimator?.cancel()
        demoAnimator = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
