package com.textgate.ai.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import com.textgate.ai.BuildConfig
import com.textgate.ai.R
import com.textgate.ai.accessibility.TextGateAccessibilityService
import com.textgate.ai.databinding.ActivitySettingsBinding
import com.textgate.ai.model.TranslationPrompts
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The app's only screen. Everything here is local configuration: nothing
 * on this screen itself reads any other app's fields — that only ever
 * happens in TextGateAccessibilityService, and only for apps switched on
 * in the "Allowed apps" list built here.
 */
class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var apiKeyStore: SecureApiKeyStore

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null

    private val modelSuggestions = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-3.7-flash",
        "gemini-3.6-flash"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // This app targets SDK 35, so the system enforces edge-to-edge by
        // default: the window content is allowed to draw underneath the
        // status bar, the display cutout (the front-camera notch), and any
        // navigation bar. Because this screen is a plain platform Activity
        // (no AppCompat/Material, by design — see the "zero production
        // dependencies" note in build.gradle.kts), it has none of the
        // automatic inset-handling those libraries provide.
        //
        // An earlier fix here called window.setDecorFitsSystemWindows(true)
        // to opt back out of edge-to-edge entirely. That corrected the
        // portrait symptom (the top card rendering behind the status bar,
        // making the master switch invisible) but a resident cutout/inset
        // gap remained in landscape: rotated, the cutout sits along the
        // layout's leading edge instead of the true top, and on this
        // device's OEM build the legacy fitSystemWindows path did not
        // reserve space for it — a sliver of the first card's text peeked
        // out from under the green ActionBar/status-bar band.
        //
        // The robust fix is to do the padding ourselves: stay in
        // edge-to-edge mode (false) and explicitly read back the system
        // bar + display cutout insets on every layout pass (orientation
        // changes included), applying them as padding on the root view.
        // This works the same way regardless of OEM quirks in the legacy
        // inset-fitting path. WindowInsets.Type and getInsets() are
        // platform APIs added in API 30; this app's minSdk is 26, hence the
        // guard — devices below API 30 cannot enforce edge-to-edge from a
        // targetSdk 35 app in the first place, so no fallback is needed
        // there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            applyInsetsAsPadding(binding.root)
        }

        settingsStore = AppSettingsStore(applicationContext)
        apiKeyStore = SecureApiKeyStore(applicationContext)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        setupMasterSwitch()
        setupAccessibilitySection()
        setupApiKeySection()
        setupModelSection()
        setupTestApiButton()
        setupAllowedAppsSection()
        setupAboutSection()
    }

    override fun onResume() {
        super.onResume()
        // Accessibility enablement can only change outside this app (in
        // system Settings), so refresh the status label every time the
        // user returns to this screen.
        refreshAccessibilityStatus()
    }

    override fun onDestroy() {
        backgroundExecutor?.shutdownNow()
        backgroundExecutor = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Window insets (edge-to-edge handling — see the comment in onCreate())
    // ---------------------------------------------------------------

    /**
     * Pads [root] by the system bar + display cutout insets on every
     * layout pass, so content never renders underneath the status bar, the
     * camera cutout, or a navigation bar, in either orientation. The
     * original (XML-declared) padding is preserved and added to, rather
     * than replaced, so this is safe to call exactly once regardless of
     * what padding the layout already specifies.
     */
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

    // ---------------------------------------------------------------
    // Master switch
    // ---------------------------------------------------------------

    private fun setupMasterSwitch() {
        binding.switchAiEnabled.isChecked = settingsStore.isAiEnabled
        binding.switchAiEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            settingsStore.isAiEnabled = isChecked
        }
    }

    // ---------------------------------------------------------------
    // Accessibility status
    // ---------------------------------------------------------------

    private fun setupAccessibilitySection() {
        refreshAccessibilityStatus()
        binding.buttonOpenAccessibilitySettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.textAccessibilityStatus.text = getString(
            if (enabled) R.string.accessibility_status_enabled else R.string.accessibility_status_disabled
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val manager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val enabledServices =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabledServices.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.packageName == packageName &&
                    serviceInfo?.name == TextGateAccessibilityService::class.java.name
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------
    // API key
    // ---------------------------------------------------------------

    private fun setupApiKeySection() {
        refreshApiKeyStatus()

        binding.buttonSaveApiKey.setOnClickListener {
            val chars = CharArray(binding.editApiKey.text.length)
            binding.editApiKey.text.getChars(0, chars.size, chars, 0)
            val saved = apiKeyStore.saveApiKey(chars)
            // Clear the on-screen field immediately after handing the key
            // off — the key is never shown again once saved.
            binding.editApiKey.text?.clear()
            if (saved) {
                Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
            }
            refreshApiKeyStatus()
        }

        binding.buttonClearApiKey.setOnClickListener {
            apiKeyStore.clearApiKey()
            Toast.makeText(this, R.string.api_key_cleared, Toast.LENGTH_SHORT).show()
            refreshApiKeyStatus()
        }
    }

    private fun refreshApiKeyStatus() {
        binding.textApiKeyStatus.text = getString(
            if (apiKeyStore.hasApiKey()) R.string.api_key_status_present else R.string.api_key_status_absent
        )
    }

    // ---------------------------------------------------------------
    // Model selection
    // ---------------------------------------------------------------

    private fun setupModelSection() {
        binding.editModel.setText(settingsStore.selectedModel)
        binding.editModel.doAfterTextChangedSafely { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                settingsStore.selectedModel = trimmed
            }
        }

        binding.layoutModelSuggestions.removeAllViews()
        val inflater = LayoutInflater.from(this)
        modelSuggestions.forEach { modelId ->
            val chip = inflater.inflate(R.layout.item_model_chip, binding.layoutModelSuggestions, false) as TextView
            chip.text = modelId
            chip.setOnClickListener {
                binding.editModel.setText(modelId)
                binding.editModel.setSelection(modelId.length)
                settingsStore.selectedModel = modelId
            }
            binding.layoutModelSuggestions.addView(chip)
        }
    }

    // ---------------------------------------------------------------
    // Test API
    // ---------------------------------------------------------------

    private fun setupTestApiButton() {
        binding.buttonTestApi.setOnClickListener { runApiTest() }
    }

    private fun runApiTest() {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            binding.textTestApiResult.text = getString(R.string.error_no_api_key)
            return
        }
        val model = settingsStore.selectedModel
        val executor = backgroundExecutor ?: return

        binding.buttonTestApi.isEnabled = false
        binding.textTestApiResult.text = getString(R.string.test_api_running)

        executor.execute {
            val result = try {
                GeminiClient.translateBlocking(
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = TranslationPrompts.EN_TRANSLATION_SYSTEM_PROMPT,
                    // Fixed, non-sensitive test string — never content from
                    // any monitored field.
                    userText = "To jest testowa wiadomość."
                )
            } catch (_: Exception) {
                GeminiClient.Result.Failure.InvalidResponse
            }
            mainHandler.post {
                binding.buttonTestApi.isEnabled = true
                binding.textTestApiResult.text = when (result) {
                    is GeminiClient.Result.Success -> getString(R.string.test_api_success)
                    is GeminiClient.Result.Failure -> getString(
                        R.string.test_api_failure,
                        describeFailure(result)
                    )
                }
            }
        }
    }

    private fun describeFailure(failure: GeminiClient.Result.Failure): String = when (failure) {
        GeminiClient.Result.Failure.Timeout -> getString(R.string.error_timeout)
        GeminiClient.Result.Failure.NetworkError -> getString(R.string.error_network)
        is GeminiClient.Result.Failure.HttpError -> getString(R.string.error_http, failure.code)
        GeminiClient.Result.Failure.EmptyResponse -> getString(R.string.error_empty_response)
        GeminiClient.Result.Failure.MissingApiKey -> getString(R.string.error_no_api_key)
        GeminiClient.Result.Failure.InvalidModel -> getString(R.string.error_generic)
        GeminiClient.Result.Failure.InvalidResponse -> getString(R.string.error_generic)
        GeminiClient.Result.Failure.HostNotAllowed -> getString(R.string.error_generic)
    }

    // ---------------------------------------------------------------
    // Allowed apps
    // ---------------------------------------------------------------

    private fun setupAllowedAppsSection() {
        // The manual per-app picker starts collapsed (see the XML:
        // layoutAppList/textAdvancedAppsHint both start GONE) — most users
        // never need it, since AppSettingsStore.DEFAULT_ALLOWED_PACKAGES
        // already covers common social/messaging apps with zero setup.
        // It's still built in the background right away so expanding it
        // feels instant rather than triggering a fresh load.
        binding.textToggleAdvancedApps.setOnClickListener { toggleAdvancedAppsVisibility() }

        binding.layoutAppList.removeAllViews()
        val loadingLabel = TextView(this).apply { text = getString(R.string.allowed_apps_loading) }
        binding.layoutAppList.addView(loadingLabel)

        val executor = backgroundExecutor ?: return
        executor.execute {
            val apps = try {
                InstalledAppsProvider.listLaunchableApps(applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
            mainHandler.post { renderAppList(apps) }
        }
    }

    private fun toggleAdvancedAppsVisibility() {
        val nowExpanded = binding.layoutAppList.visibility != View.VISIBLE
        binding.layoutAppList.visibility = if (nowExpanded) View.VISIBLE else View.GONE
        binding.textAdvancedAppsHint.visibility = if (nowExpanded) View.VISIBLE else View.GONE
        binding.textToggleAdvancedApps.text = getString(
            if (nowExpanded) R.string.btn_hide_advanced_apps else R.string.btn_show_advanced_apps
        )
    }

    private fun renderAppList(apps: List<LaunchableApp>) {
        binding.layoutAppList.removeAllViews()
        if (apps.isEmpty()) {
            val empty = TextView(this).apply { text = getString(R.string.allowed_apps_empty) }
            binding.layoutAppList.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        apps.forEach { app ->
            val row = inflater.inflate(R.layout.item_app_row, binding.layoutAppList, false) as CheckBox
            row.text = "${app.label}\n${app.packageName}"
            row.isChecked = settingsStore.isPackageAllowed(app.packageName)
            row.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                settingsStore.setPackageAllowed(app.packageName, isChecked)
            }
            binding.layoutAppList.addView(row)
        }
    }

    // ---------------------------------------------------------------
    // About
    // ---------------------------------------------------------------

    private fun setupAboutSection() {
        binding.textVersion.text = getString(R.string.about_version_label) + ": " + BuildConfig.VERSION_NAME
    }
}

/** Small local helper to avoid pulling in androidx.core's addTextChangedListener KTX extension. */
private fun android.widget.EditText.doAfterTextChangedSafely(action: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            action(s?.toString().orEmpty())
        }
    })
}
