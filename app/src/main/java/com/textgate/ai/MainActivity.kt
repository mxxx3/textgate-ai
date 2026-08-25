package com.textgate.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.textgate.ai.conversation.ConversationTabController
import com.textgate.ai.databinding.ActivityMainBinding
import com.textgate.ai.live.LiveTabController
import com.textgate.ai.settings.SettingsActivity
import com.textgate.ai.translate.TranslateTabController

/**
 * The app's launcher Activity as of v2 — hosts the bottom navigation
 * (Tłumacz / Rozmowa / Na żywo / Ustawienia) described in the v2 rebuild
 * spec. Deliberately NOT built on Jetpack Navigation or Fragments (platform
 * Fragment is a deprecated API this app has never used, and the AndroidX
 * fragment library would be a new dependency) — each tab is a plain
 * inflated View kept alive for the Activity's lifetime, with only one
 * visible at a time (see [showTab]). This is the same "plain platform
 * Views, hand-rolled" approach [com.textgate.ai.settings.SettingsActivity]
 * already uses, extended to cover navigation between screens too.
 *
 * The Ustawienia tab does NOT get its own in-place content view — tapping
 * it simply starts [SettingsActivity] exactly as before, unchanged. This
 * was a deliberate choice to satisfy the spec's own explicit instruction
 * not to rebuild or duplicate an existing, working screen: reorganizing
 * activity_settings.xml into labeled sections (see that Activity) achieves
 * the requested reorganization without touching any of its working logic
 * or view ids.
 *
 * Tłumacz reuses the existing text-translation pipeline
 * ([com.textgate.ai.network.TranslationOrchestrator]) unchanged. Rozmowa
 * and Na żywo are new, [com.textgate.ai.live.GeminiLiveClient]-backed voice
 * modes — see [TranslateTabController], [ConversationTabController], and
 * [LiveTabController] for each tab's own logic, kept in their own files so
 * this Activity stays a thin shell.
 */
class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var translateTab: TranslateTabController
    private lateinit var conversationTab: ConversationTabController
    private lateinit var liveTab: LiveTabController

    private enum class Tab { TRANSLATE, CONVERSATION, LIVE }

    private var currentTab = Tab.TRANSLATE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Same edge-to-edge inset handling as SettingsActivity — see that
        // Activity's onCreate for the full reasoning (API 30+ only; below
        // that, a targetSdk 35 app cannot be forced edge-to-edge anyway).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            applyInsetsAsPadding(binding.rootContainer)
        }

        translateTab = TranslateTabController(this, binding.contentTranslate)
        conversationTab = ConversationTabController(this, binding.contentConversation)
        liveTab = LiveTabController(this, binding.contentLive)

        setupBottomNav()
        showTab(Tab.TRANSLATE)
    }

    override fun onDestroy() {
        translateTab.onDestroy()
        conversationTab.onDestroy()
        liveTab.onDestroy()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        liveTab.onStart()
    }

    override fun onStop() {
        // Rozmowa is deliberately foreground-only (see
        // ConversationTabController's class doc) — always ends when the
        // app backgrounds. Na żywo is the opposite by design: it survives
        // backgrounding via its own Foreground Service, so liveTab.onStop()
        // only detaches this Activity's UI listener, never stops the
        // session itself — see LiveTabController.
        conversationTab.onStop()
        liveTab.onStop()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        translateTab.onRequestPermissionsResult(requestCode, permissions, grantResults)
        conversationTab.onRequestPermissionsResult(requestCode, permissions, grantResults)
        liveTab.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun applyInsetsAsPadding(root: View) {
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun setupBottomNav() {
        binding.navTranslate.setOnClickListener { showTab(Tab.TRANSLATE) }
        binding.navConversation.setOnClickListener { showTab(Tab.CONVERSATION) }
        binding.navLive.setOnClickListener { showTab(Tab.LIVE) }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        binding.contentTranslate.root.visibility = if (tab == Tab.TRANSLATE) View.VISIBLE else View.GONE
        binding.contentConversation.root.visibility = if (tab == Tab.CONVERSATION) View.VISIBLE else View.GONE
        binding.contentLive.root.visibility = if (tab == Tab.LIVE) View.VISIBLE else View.GONE

        setNavItemSelected(binding.navTranslate, binding.navTranslateIcon, binding.navTranslateLabel, tab == Tab.TRANSLATE)
        setNavItemSelected(binding.navConversation, binding.navConversationIcon, binding.navConversationLabel, tab == Tab.CONVERSATION)
        setNavItemSelected(binding.navLive, binding.navLiveIcon, binding.navLiveLabel, tab == Tab.LIVE)
        // Settings is a startActivity() shortcut, never the "current" tab —
        // its icon/label are left in the unselected state at all times.
        setNavItemSelected(binding.navSettings, binding.navSettingsIcon, binding.navSettingsLabel, false)
    }

    private fun setNavItemSelected(container: LinearLayout, icon: ImageView, label: TextView, selected: Boolean) {
        val color = getColor(if (selected) R.color.tg_primary else R.color.tg_text_secondary)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        label.setTextColor(color)
    }

    companion object {
        // Distinct request codes per tab controller — three different
        // screens can each trigger a RECORD_AUDIO (and, for Na żywo, also
        // POST_NOTIFICATIONS) prompt independently, and onRequestPermissionsResult
        // forwards every result to all three controllers (see above); a
        // shared code would make an unrelated controller react to a grant
        // it did not itself request.
        const val PERMISSION_REQUEST_RECORD_AUDIO_TRANSLATE = 1001
        const val PERMISSION_REQUEST_RECORD_AUDIO_CONVERSATION = 1002
        const val PERMISSION_REQUEST_RECORD_AUDIO_LIVE = 1003

        fun hasRecordAudioPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }
}
