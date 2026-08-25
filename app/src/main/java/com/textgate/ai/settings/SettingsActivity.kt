package com.textgate.ai.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.textgate.ai.BuildConfig
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R
import com.textgate.ai.accessibility.AccessibilityDisclosureActivity
import com.textgate.ai.accessibility.TextGateAccessibilityService
import com.textgate.ai.databinding.ActivitySettingsBinding
import com.textgate.ai.model.HeadsetDisconnectBehavior
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.model.TranslationPrompts
import com.textgate.ai.model.UserGender
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.network.ModelAvailabilityStore
import com.textgate.ai.network.TranslationOrchestrator
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import com.textgate.ai.security.TriggerDetector
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
    private lateinit var availabilityStore: ModelAvailabilityStore

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null

    // Ordered by what actually has free-tier headroom on the app owner's
    // own Google AI Studio account (checked via aistudio.google.com's
    // rate-limit dashboard): the two "Flash Lite" models carry by far the
    // highest daily quota, gemini-2.5-flash is a solid known-good
    // fallback, and gemini-3.7-flash is the most capable option for the
    // rare case a translation needs more than Flash-Lite-level reasoning.
    // gemini-2.5-pro is deliberately left out — it showed a flat 0/0 quota
    // on that dashboard, so suggesting it here would just invite another
    // guaranteed-to-fail tap. See AppSettingsStore.DEFAULT_MODEL.
    private val modelSuggestions = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash",
        "gemini-3.7-flash"
    )

    companion object {
        /** Google AI Studio's "API keys" page — the exact place a
         * first-time user needs to land to create a free key, confirmed
         * against ai.google.dev/gemini-api/docs/api-key. */
        private const val GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey"
    }

    /**
     * Applies the user's chosen "App interface language" (see
     * [LocaleHelper]) to this Activity's own Context — an Activity can be
     * individually re-created by the system with a fresh Configuration
     * (e.g. after rotation), so relying on the override applied once in
     * [com.textgate.ai.TextGateApplication] alone is not reliable here on
     * every Android version.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

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
        availabilityStore = ModelAvailabilityStore(applicationContext)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        setupMasterSwitch()
        setupLanguageSection()
        setupUserGenderSection()
        setupHeadsetDisconnectSection()
        setupAccessibilitySection()
        setupApiKeySection()
        setupModelSection()
        setupTestApiButton()
        setupAllowedAppsSection()
        setupPrivacySection()
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
    // Language: one collapsible list driving both the long-press bubble's
    // default target language AND the app's own interface language
    // ---------------------------------------------------------------

    /**
     * As of v1.4.0 this single dropdown (a platform [android.widget.Spinner]
     * — the "zwinięta lista", collapsed list, the user asked for) replaces
     * the old two-radio-button English/Polish picker and covers all 40
     * languages in [Languages.ALL]. Each entry is shown by
     * [SupportedLanguage.nativeName] (that language's own name, in its own
     * script — exactly like every native OS language picker), never
     * translated, so it reads correctly to its own speaker regardless of
     * what the app's current interface language happens to be.
     *
     * Picking a language here changes BOTH settings at once — the
     * long-press bubble's default translation target
     * ([AppSettingsStore.bubbleTargetLanguage]) and the app's own interface
     * language ([AppSettingsStore.appInterfaceLanguage]) — per the user's
     * explicit answer ("Jedno i drugie", both) when this was clarified
     * during the v1.4.0 rebuild: one list, one choice, the whole app
     * follows. [recreate] is called immediately afterward so every string
     * on screen updates without the user needing to leave and reopen
     * Settings.
     */
    private fun setupLanguageSection() {
        val languages = Languages.ALL
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.nativeName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAppLanguage.adapter = adapter

        val currentCode = settingsStore.bubbleTargetLanguage.code
        val currentIndex = languages.indexOfFirst { it.code == currentCode }.let { if (it >= 0) it else 0 }
        binding.spinnerAppLanguage.setSelection(currentIndex, false)

        // The listener is attached only after the initial layout pass
        // (via post{}) rather than right away — Spinner fires
        // onItemSelected for the programmatic setSelection() call above
        // too, and attaching the listener beforehand would immediately
        // re-save the already-current language and call recreate() on
        // every Settings screen open, which is both wasteful and (on some
        // OEM builds) visibly janky.
        binding.spinnerAppLanguage.post {
            binding.spinnerAppLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = languages.getOrNull(position) ?: return
                    if (selected.code == settingsStore.bubbleTargetLanguage.code &&
                        selected.code == settingsStore.appInterfaceLanguage
                    ) {
                        return
                    }
                    settingsStore.bubbleTargetLanguage = TriggerDetector.Target(selected.code)
                    settingsStore.appInterfaceLanguage = selected.code
                    recreate()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    // ---------------------------------------------------------------
    // User gender preference (v1.7.0+)
    // ---------------------------------------------------------------

    /** Fixed display order for the gender spinner — index into this list is
     * how [android.widget.AdapterView.OnItemSelectedListener.onItemSelected]'s
     * `position` is translated back into a [UserGender]. [UserGender.AUTO]
     * is deliberately first/default, matching how it behaves in
     * [TranslationPrompts.systemPromptFor] (no gender clause added at all). */
    private val genderOptions = listOf(UserGender.AUTO, UserGender.MALE, UserGender.FEMALE)

    private fun genderLabelRes(gender: UserGender): Int = when (gender) {
        UserGender.AUTO -> R.string.label_gender_auto
        UserGender.MALE -> R.string.label_gender_male
        UserGender.FEMALE -> R.string.label_gender_female
    }

    /**
     * Same Spinner + deferred-listener pattern as [setupLanguageSection]
     * above, for the same reason: attaching the selection listener before
     * the initial [android.widget.Spinner.setSelection] call would fire it
     * immediately for that programmatic selection too, re-saving the
     * already-current value on every Settings screen open.
     *
     * This preference is read ONLY by
     * [com.textgate.ai.accessibility.TextGateAccessibilityService.confirmAndProcess]
     * (the typed `?xx`-trigger path) — see [AppSettingsStore.userGender]'s
     * doc comment for why the long-press "translate a received message"
     * bubble must never read it. Nothing in this Activity needs to enforce
     * that; it is simply never referenced from that other code path.
     */
    private fun setupUserGenderSection() {
        val labels = genderOptions.map { getString(genderLabelRes(it)) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerUserGender.adapter = adapter

        val currentIndex = genderOptions.indexOf(settingsStore.userGender).let { if (it >= 0) it else 0 }
        binding.spinnerUserGender.setSelection(currentIndex, false)

        binding.spinnerUserGender.post {
            binding.spinnerUserGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = genderOptions.getOrNull(position) ?: return
                    settingsStore.userGender = selected
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    // ---------------------------------------------------------------
    // Headset-disconnect behavior (v2, "Audio i Live" section)
    // ---------------------------------------------------------------

    /** Fixed display order — [HeadsetDisconnectBehavior.PAUSE_TRANSLATION]
     * (the privacy-preserving default) first, matching how it behaves in
     * [com.textgate.ai.live.LiveTranslationService]. Same Spinner +
     * deferred-listener pattern as [setupUserGenderSection] above, for the
     * same reason: attaching the listener before the initial
     * [android.widget.Spinner.setSelection] call would fire it immediately
     * for that programmatic selection too. */
    private val headsetDisconnectOptions = listOf(
        HeadsetDisconnectBehavior.PAUSE_TRANSLATION,
        HeadsetDisconnectBehavior.SWITCH_TO_SPEAKER
    )

    private fun headsetDisconnectLabelRes(behavior: HeadsetDisconnectBehavior): Int = when (behavior) {
        HeadsetDisconnectBehavior.PAUSE_TRANSLATION -> R.string.label_headset_disconnect_pause
        HeadsetDisconnectBehavior.SWITCH_TO_SPEAKER -> R.string.label_headset_disconnect_speaker
    }

    private fun setupHeadsetDisconnectSection() {
        val labels = headsetDisconnectOptions.map { getString(headsetDisconnectLabelRes(it)) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerHeadsetDisconnectBehavior.adapter = adapter

        val currentIndex = headsetDisconnectOptions.indexOf(settingsStore.headsetDisconnectBehavior)
            .let { if (it >= 0) it else 0 }
        binding.spinnerHeadsetDisconnectBehavior.setSelection(currentIndex, false)

        binding.spinnerHeadsetDisconnectBehavior.post {
            binding.spinnerHeadsetDisconnectBehavior.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = headsetDisconnectOptions.getOrNull(position) ?: return
                    settingsStore.headsetDisconnectBehavior = selected
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    // ---------------------------------------------------------------
    // Accessibility status
    // ---------------------------------------------------------------

    private fun setupAccessibilitySection() {
        refreshAccessibilityStatus()
        binding.buttonOpenAccessibilitySettings.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                // Already granted — this tap can only be to review or turn
                // it back off, so go straight to the system screen as
                // before. The mandatory disclosure (below) is specifically
                // about what happens BEFORE the permission is requested;
                // Google's policy does not require re-showing it to a user
                // who already granted the permission.
                openSystemAccessibilitySettings()
            } else {
                // Not yet granted: show the in-app "prominent disclosure"
                // screen first (see AccessibilityDisclosureActivity's doc
                // comment). Only that screen's own "I agree" button — never
                // this one — is allowed to open the system screen that
                // actually requests the permission.
                startActivity(Intent(this, AccessibilityDisclosureActivity::class.java))
            }
        }
    }

    private fun openSystemAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
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
        renderApiKeyList()
        refreshApiKeyStatus()

        // Opens Google AI Studio's "Create API key" page directly in the
        // browser — the exact page a first-time user needs, so they are
        // never left guessing which of Google's many developer sites is
        // the right one. Wrapped in try/catch like every other
        // startActivity() call in this screen: if no browser can handle
        // the intent (unlikely, but not impossible on a stripped-down
        // device), fail with a toast rather than crash.
        binding.buttonGetApiKey.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_API_KEY_URL)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }

        binding.textToggleApiKeyInstructions.setOnClickListener {
            toggleApiKeyInstructionsVisibility()
        }

        // Appends a new key to the store rather than replacing whatever was
        // there before — this is the "Add key" button in the multi-key
        // (v1.6.0+) UI, not a single-slot save.
        binding.buttonAddApiKey.setOnClickListener {
            val chars = CharArray(binding.editApiKey.text.length)
            binding.editApiKey.text.getChars(0, chars.size, chars, 0)
            val added = apiKeyStore.addKey(chars)
            // Clear the on-screen field immediately after handing the key
            // off — the key is never shown again once saved.
            binding.editApiKey.text?.clear()
            if (added) {
                Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
            }
            renderApiKeyList()
            refreshApiKeyStatus()
        }

        // "Remove all" — same button id/label as the old single-key
        // "Remove saved key" action, now clearing the entire list at once.
        // Per-key removal is handled by each row's own remove button (see
        // renderApiKeyList).
        binding.buttonClearApiKey.setOnClickListener {
            apiKeyStore.clearAllKeys()
            Toast.makeText(this, R.string.api_key_cleared, Toast.LENGTH_SHORT).show()
            renderApiKeyList()
            refreshApiKeyStatus()
        }
    }

    /**
     * Rebuilds [binding.layoutApiKeyList] from scratch: one
     * [R.layout.item_api_key_row] per stored key (showing its last 4
     * characters, and a " · active" marker on whichever key
     * [SecureApiKeyStore.activeKeyId] currently points at), or a single
     * empty-state label when nothing is stored yet. Called after every
     * mutation (add / remove one / clear all) so the list is always exactly
     * what [SecureApiKeyStore] holds — never a stale in-memory copy.
     */
    private fun renderApiKeyList() {
        binding.layoutApiKeyList.removeAllViews()
        val keys = apiKeyStore.listKeys()
        if (keys.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.api_keys_empty)
                textSize = 13f
                setTextColor(getColor(R.color.tg_text_secondary))
            }
            binding.layoutApiKeyList.addView(empty)
            return
        }

        val activeId = apiKeyStore.activeKeyId()
        val inflater = LayoutInflater.from(this)
        keys.forEach { key ->
            val row = inflater.inflate(R.layout.item_api_key_row, binding.layoutApiKeyList, false)
            val label = row.findViewById<TextView>(R.id.textApiKeyRowLabel)
            val removeButton = row.findViewById<android.widget.Button>(R.id.buttonRemoveApiKey)

            label.text = if (key.id == activeId) {
                getString(R.string.api_key_row_label_active, key.last4)
            } else {
                getString(R.string.api_key_row_label, key.last4)
            }

            removeButton.setOnClickListener {
                apiKeyStore.removeKey(key.id)
                renderApiKeyList()
                refreshApiKeyStatus()
            }

            binding.layoutApiKeyList.addView(row)
        }
    }

    private fun refreshApiKeyStatus() {
        val count = apiKeyStore.keyCount()
        binding.textApiKeyStatus.text = if (count == 0) {
            getString(R.string.api_keys_empty)
        } else {
            val activeLast4 = apiKeyStore.listKeys()
                .firstOrNull { it.id == apiKeyStore.activeKeyId() }
                ?.last4
                .orEmpty()
            getString(R.string.api_key_status_format, count, activeLast4)
        }
    }

    /** Same expand/collapse pattern as [toggleAdvancedAppsVisibility] below,
     * reused here for the "how do I get an API key" step-by-step guide —
     * collapsed by default so the screen isn't overwhelming for anyone who
     * already knows what to do. */
    private fun toggleApiKeyInstructionsVisibility() {
        val nowExpanded = binding.textApiKeyInstructionsBody.visibility != View.VISIBLE
        binding.textApiKeyInstructionsBody.visibility = if (nowExpanded) View.VISIBLE else View.GONE
        binding.textToggleApiKeyInstructions.text = getString(
            if (nowExpanded) R.string.btn_hide_api_key_instructions else R.string.btn_show_api_key_instructions
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

    /**
     * Runs a real request through the SAME rotation path a live translation
     * uses ([KeyRotationTranslator.translateWithRotation]) rather than
     * testing only the active key directly — this way "Test API" tells the
     * user whether translation will actually work right now (including
     * transparently rotating past an exhausted key), not just whether one
     * particular key happens to be good.
     */
    private fun runApiTest() {
        if (!apiKeyStore.hasAnyKey()) {
            binding.textTestApiResult.text = getString(R.string.error_no_api_key)
            return
        }
        val model = settingsStore.selectedModel
        val executor = backgroundExecutor ?: return

        binding.buttonTestApi.isEnabled = false
        binding.textTestApiResult.text = getString(R.string.test_api_running)

        executor.execute {
            val result = try {
                TranslationOrchestrator.translateText(
                    apiKeyStore = apiKeyStore,
                    availabilityStore = availabilityStore,
                    requestedModel = model,
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
                // The active key may have changed if rotation kicked in
                // during the test — reflect that immediately.
                renderApiKeyList()
                refreshApiKeyStatus()
            }
        }
    }

    private fun describeFailure(failure: GeminiClient.Result.Failure): String = when (failure) {
        GeminiClient.Result.Failure.Timeout -> getString(R.string.error_timeout)
        GeminiClient.Result.Failure.NetworkError -> getString(R.string.error_network)
        is GeminiClient.Result.Failure.HttpError -> getString(R.string.error_http, failure.code)
        GeminiClient.Result.Failure.EmptyResponse -> getString(R.string.error_empty_response)
        GeminiClient.Result.Failure.MissingApiKey -> getString(R.string.error_no_api_key)
        is GeminiClient.Result.Failure.AllKeysExhausted -> getString(R.string.error_all_keys_exhausted)
        is GeminiClient.Result.Failure.QuotaExceeded -> getString(R.string.error_quota_exceeded)
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
            val row = inflater.inflate(R.layout.item_app_row, binding.layoutAppList, false)
            val icon = row.findViewById<ImageView>(R.id.imageAppIcon)
            val label = row.findViewById<TextView>(R.id.textAppLabel)
            val checkbox = row.findViewById<CheckBox>(R.id.checkboxAppAllowed)

            // Real icon when PackageManager supplied one; otherwise a
            // neutral placeholder — never a fabricated stand-in (see
            // LaunchableApp's doc comment in InstalledAppsProvider.kt).
            if (app.icon != null) {
                icon.setImageDrawable(app.icon)
            } else {
                icon.setImageResource(R.drawable.ic_default_app)
            }
            // Friendly app name only — the package name subtext
            // ("com.example.app") that used to be shown here has been
            // removed per user request.
            label.text = app.label
            checkbox.isChecked = settingsStore.isPackageAllowed(app.packageName)
            checkbox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                settingsStore.setPackageAllowed(app.packageName, isChecked)
            }
            // The checkbox itself has clicks disabled (see item_app_row.xml)
            // so the whole row is the tap target, toggling it exactly once.
            row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }

            binding.layoutAppList.addView(row)
        }
    }

    // ---------------------------------------------------------------
    // Privacy
    // ---------------------------------------------------------------

    /**
     * The short, always-visible privacy_notice_body text used to end with
     * "see the README for the exact rules" — but README.md is a repo-only
     * developer document, never packaged into the APK, so a real user
     * tapping that had nowhere to go. This button replaces that dead
     * pointer with an actual, in-app, plain-language full explanation
     * (privacy_full_policy_body), shown in a simple platform AlertDialog —
     * no new dependency needed, consistent with this app's zero-production-
     * dependency principle (see build.gradle.kts).
     */
    private fun setupPrivacySection() {
        binding.buttonFullPrivacyPolicy.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.privacy_full_policy_title)
                .setMessage(R.string.privacy_full_policy_body)
                .setPositiveButton(R.string.btn_close_dialog, null)
                .show()
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
