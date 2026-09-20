package com.textgate.ai.tutorial

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.textgate.ai.LocaleHelper
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.databinding.ActivityTutorialBinding
import com.textgate.ai.security.AppSettingsStore
import kotlin.math.abs

/**
 * First-run product tour shown after setup is complete and before MainActivity.
 *
 * The copy comes from [TutorialCopyProvider], keyed by the language selected
 * inside TextGate AI, so it does not depend on the phone/browser language.
 * Illustrations contain no language-specific text and are animated locally.
 *
 * No network request is made by this Activity.
 */
class TutorialActivity : Activity() {

    private lateinit var binding: ActivityTutorialBinding
    private lateinit var copy: TutorialCopy
    private val slideViews = mutableListOf<View>()
    private val dotViews = mutableListOf<View>()
    private var currentIndex = 0
    private var illustrationAnimator: Animator? = null
    private var replayMode = false

    private data class SlideSpec(
        val primaryRes: Int,
        val accentRes: Int,
        val animation: AnimationKind
    )

    private enum class AnimationKind {
        WELCOME,
        TRANSLATE,
        TYPED_TRIGGER,
        LONG_PRESS,
        VOICE,
        PRIVACY
    }

    private val specs = listOf(
        SlideSpec(R.mipmap.ic_launcher, R.drawable.ic_tutorial_translate, AnimationKind.WELCOME),
        SlideSpec(R.drawable.ic_tutorial_translate, R.drawable.ic_tutorial_mic, AnimationKind.TRANSLATE),
        SlideSpec(R.drawable.ic_tutorial_keyboard, R.drawable.ic_tutorial_translate, AnimationKind.TYPED_TRIGGER),
        SlideSpec(R.drawable.ic_tutorial_chat, R.drawable.ic_tutorial_touch, AnimationKind.LONG_PRESS),
        SlideSpec(R.drawable.ic_tutorial_headset, R.drawable.ic_tutorial_mic, AnimationKind.VOICE),
        SlideSpec(R.drawable.ic_tutorial_shield, R.drawable.ic_tutorial_lock, AnimationKind.PRIVACY)
    )

    companion object {
        const val CURRENT_VERSION = 1
        private const val EXTRA_REPLAY = "replay"

        fun needsToBeShown(context: Context): Boolean =
            AppSettingsStore(context).tutorialVersionSeen < CURRENT_VERSION

        fun intent(context: Context, replay: Boolean = false): Intent =
            Intent(context, TutorialActivity::class.java)
                .putExtra(EXTRA_REPLAY, replay)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)
        replayMode = intent.getBooleanExtra(EXTRA_REPLAY, false)
        copy = TutorialCopyProvider.forContext(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            applyInsetsAsPadding(binding.rootTutorial)
        }

        buildSlides()
        buildDots()
        setupControls()
        setupSwipe()
        showSlide(0, forward = true, animateTransition = false)
    }

    override fun onDestroy() {
        illustrationAnimator?.cancel()
        illustrationAnimator = null
        super.onDestroy()
    }

    private fun applyInsetsAsPadding(root: View) {
        val originalLeft = root.paddingLeft
        val originalTop = root.paddingTop
        val originalRight = root.paddingRight
        val originalBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(
                originalLeft + bars.left,
                originalTop + bars.top,
                originalRight + bars.right,
                originalBottom + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildSlides() {
        check(copy.slides.size == specs.size) {
            "Tutorial copy must have exactly ${specs.size} slides"
        }

        val inflater = LayoutInflater.from(this)
        copy.slides.zip(specs).forEach { (slideCopy, spec) ->
            val slide = inflater.inflate(R.layout.item_tutorial_slide, binding.tutorialFlipper, false)
            slide.findViewById<TextView>(R.id.textTutorialTitle).text = slideCopy.title
            slide.findViewById<TextView>(R.id.textTutorialBody).text = slideCopy.body
            slide.findViewById<ImageView>(R.id.imageTutorialPrimary).setImageResource(spec.primaryRes)
            slide.findViewById<ImageView>(R.id.imageTutorialAccent).setImageResource(spec.accentRes)
            binding.tutorialFlipper.addView(slide)
            slideViews += slide
        }
    }

    private fun buildDots() {
        repeat(specs.size) {
            val dot = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            binding.layoutTutorialDots.addView(dot)
            dotViews += dot
        }
    }

    private fun setupControls() {
        binding.textTutorialSkip.text = copy.skip
        binding.buttonTutorialBack.text = copy.back
        binding.buttonTutorialNext.text = copy.next

        binding.textTutorialSkip.setOnClickListener { completeTutorial() }
        binding.buttonTutorialBack.setOnClickListener { previousSlide() }
        binding.buttonTutorialNext.setOnClickListener {
            if (currentIndex == specs.lastIndex) completeTutorial() else nextSlide()
        }
    }

    private fun setupSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) < dp(56) || abs(velocityX) < dp(160)) return false

                val rtl = binding.rootTutorial.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val forward = if (rtl) dx > 0 else dx < 0
                if (forward) nextSlide() else previousSlide()
                return true
            }
        })

        val listener = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
        }
        binding.tutorialFlipper.setOnTouchListener(listener)
        slideViews.forEach { it.setOnTouchListener(listener) }
    }

    private fun nextSlide() {
        if (currentIndex >= specs.lastIndex) return
        showSlide(currentIndex + 1, forward = true, animateTransition = true)
    }

    private fun previousSlide() {
        if (currentIndex <= 0) return
        showSlide(currentIndex - 1, forward = false, animateTransition = true)
    }

    private fun showSlide(index: Int, forward: Boolean, animateTransition: Boolean) {
        currentIndex = index.coerceIn(0, specs.lastIndex)

        if (animateTransition) {
            val rtl = binding.rootTutorial.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val visualForward = if (rtl) !forward else forward
            binding.tutorialFlipper.inAnimation = slideAnimation(
                if (visualForward) 1f else -1f,
                0f
            )
            binding.tutorialFlipper.outAnimation = slideAnimation(
                0f,
                if (visualForward) -1f else 1f
            )
        } else {
            binding.tutorialFlipper.inAnimation = null
            binding.tutorialFlipper.outAnimation = null
        }

        binding.tutorialFlipper.displayedChild = currentIndex
        binding.buttonTutorialBack.visibility =
            if (currentIndex == 0) View.INVISIBLE else View.VISIBLE
        binding.buttonTutorialNext.text =
            if (currentIndex == specs.lastIndex) copy.start else copy.next
        binding.textTutorialSkip.visibility =
            if (currentIndex == specs.lastIndex) View.INVISIBLE else View.VISIBLE

        dotViews.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i == currentIndex) R.drawable.bg_tutorial_dot_active
                else R.drawable.bg_tutorial_dot_inactive
            )
        }

        startIllustrationAnimation()
    }

    private fun slideAnimation(fromX: Float, toX: Float): Animation =
        TranslateAnimation(
            Animation.RELATIVE_TO_SELF,
            fromX,
            Animation.RELATIVE_TO_SELF,
            toX,
            Animation.RELATIVE_TO_SELF,
            0f,
            Animation.RELATIVE_TO_SELF,
            0f
        ).apply {
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
        }

    private fun startIllustrationAnimation() {
        illustrationAnimator?.cancel()
        illustrationAnimator = null

        val slide = slideViews[currentIndex]
        val primary = slide.findViewById<ImageView>(R.id.imageTutorialPrimary)
        val accentBubble = slide.findViewById<FrameLayout>(R.id.tutorialAccentBubble)

        primary.scaleX = 1f
        primary.scaleY = 1f
        primary.translationX = 0f
        primary.translationY = 0f
        primary.rotation = 0f
        accentBubble.scaleX = 1f
        accentBubble.scaleY = 1f
        accentBubble.translationX = 0f
        accentBubble.translationY = 0f
        accentBubble.rotation = 0f
        accentBubble.alpha = 1f

        fun infinite(
            target: View,
            property: String,
            vararg values: Float,
            duration: Long = 900L
        ) = ObjectAnimator.ofFloat(target, property, *values).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val animators = when (specs[currentIndex].animation) {
            AnimationKind.WELCOME -> listOf(
                infinite(primary, View.SCALE_X.name, 1f, 1.05f, duration = 1100L),
                infinite(primary, View.SCALE_Y.name, 1f, 1.05f, duration = 1100L),
                infinite(accentBubble, View.ROTATION.name, -7f, 7f, duration = 850L)
            )
            AnimationKind.TRANSLATE -> listOf(
                infinite(accentBubble, View.TRANSLATION_X.name, -dp(12).toFloat(), dp(12).toFloat(), duration = 800L),
                infinite(primary, View.SCALE_X.name, 0.98f, 1.03f, duration = 1100L),
                infinite(primary, View.SCALE_Y.name, 0.98f, 1.03f, duration = 1100L)
            )
            AnimationKind.TYPED_TRIGGER -> listOf(
                infinite(accentBubble, View.TRANSLATION_Y.name, dp(12).toFloat(), -dp(8).toFloat(), duration = 700L),
                infinite(accentBubble, View.ALPHA.name, 0.55f, 1f, duration = 700L)
            )
            AnimationKind.LONG_PRESS -> listOf(
                infinite(accentBubble, View.TRANSLATION_Y.name, dp(14).toFloat(), 0f, duration = 650L),
                infinite(accentBubble, View.SCALE_X.name, 0.9f, 1.05f, duration = 650L),
                infinite(accentBubble, View.SCALE_Y.name, 0.9f, 1.05f, duration = 650L)
            )
            AnimationKind.VOICE -> listOf(
                infinite(primary, View.SCALE_X.name, 0.97f, 1.04f, duration = 900L),
                infinite(primary, View.SCALE_Y.name, 0.97f, 1.04f, duration = 900L),
                infinite(accentBubble, View.SCALE_X.name, 0.8f, 1.15f, duration = 600L),
                infinite(accentBubble, View.SCALE_Y.name, 0.8f, 1.15f, duration = 600L)
            )
            AnimationKind.PRIVACY -> listOf(
                infinite(primary, View.SCALE_X.name, 1f, 1.045f, duration = 1150L),
                infinite(primary, View.SCALE_Y.name, 1f, 1.045f, duration = 1150L),
                infinite(accentBubble, View.ALPHA.name, 0.65f, 1f, duration = 750L)
            )
        }

        illustrationAnimator = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }

    private fun completeTutorial() {
        AppSettingsStore(this).tutorialVersionSeen = CURRENT_VERSION
        if (replayMode) {
            finish()
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
