package com.textgate.ai.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import com.textgate.ai.LocaleHelper
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.accessibility.AccessibilityDisclosureActivity
import com.textgate.ai.databinding.ActivitySetupBinding
import com.textgate.ai.model.TranslationPrompts
import com.textgate.ai.model.Languages
import com.textgate.ai.model.UserGender
import com.textgate.ai.model.pickerLabel
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.network.ModelAvailabilityStore
import com.textgate.ai.network.TranslationOrchestrator
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import com.textgate.ai.security.TriggerDetector
import com.textgate.ai.settings.SettingsActivity
import java.util.concurrent.Executors

class SetupActivity : Activity() {
    private lateinit var binding: ActivitySetupBinding
    private val testExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.setup_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val root = binding.root
            val left = root.paddingLeft
            val top = root.paddingTop
            val right = root.paddingRight
            val bottom = root.paddingBottom
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }

        setupPreferencePickers()

        binding.buttonGetApiKey.setOnClickListener {
            if (!open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))) showOpenError()
        }
        binding.buttonSaveApiKey.setOnClickListener {
            val chars = CharArray(binding.editApiKey.text.length)
            binding.editApiKey.text.getChars(0, chars.size, chars, 0)
            val saved = try {
                SecureApiKeyStore(this).addKey(chars)
            } catch (_: Exception) {
                chars.fill('\u0000')
                false
            }
            binding.editApiKey.text.clear()
            binding.textApiTestResult.text = ""
            if (saved) SetupReadiness.recordApiTest(this, false)
            Toast.makeText(this, if (saved) R.string.api_key_saved else R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
            refresh()
        }
        binding.buttonTestApi.setOnClickListener {
            if (!SetupReadiness.status(this).apiKeyReady) {
                binding.textApiTestResult.setText(R.string.error_no_api_key)
                return@setOnClickListener
            }
            binding.buttonTestApi.isEnabled = false
            binding.textApiTestResult.setText(R.string.test_api_running)
            val model = AppSettingsStore(this).selectedModel
            testExecutor.execute {
                val result = try {
                    TranslationOrchestrator.translateText(
                        apiKeyStore = SecureApiKeyStore(this),
                        availabilityStore = ModelAvailabilityStore(this),
                        requestedModel = model,
                        systemPrompt = TranslationPrompts.EN_TRANSLATION_SYSTEM_PROMPT,
                        userText = "To jest testowa wiadomość."
                    )
                } catch (_: Exception) {
                    GeminiClient.Result.Failure.InvalidResponse
                }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    SetupReadiness.recordApiTest(this, result is GeminiClient.Result.Success)
                    binding.buttonTestApi.isEnabled = true
                    binding.textApiTestResult.setText(
                        if (result is GeminiClient.Result.Success) R.string.test_api_success
                        else R.string.setup_api_test_failed
                    )
                    refresh()
                }
            }
        }
        binding.buttonOpenSettings.setOnClickListener {
            if (!open(Intent(this, SettingsActivity::class.java))) showOpenError()
        }
        binding.buttonAccessibility.setOnClickListener {
            if (!open(Intent(this, AccessibilityDisclosureActivity::class.java))) showOpenError()
        }
        binding.buttonEnableTrigger.setOnClickListener {
            AppSettingsStore(this).isAiEnabled = true
            refresh()
        }
        binding.buttonBattery.setOnClickListener {
            if (!open(SetupGuideActivity.intent(this, SetupGuideOverlay.Destination.BATTERY))) showOpenError()
        }
        binding.buttonAutostart.setOnClickListener {
            if (!open(SetupGuideActivity.intent(this, SetupGuideOverlay.Destination.AUTOSTART))) showOpenError()
        }
        binding.groupBattery.setOnCheckedChangeListener { _, id ->
            val choice = when (id) {
                R.id.optionBatteryEnabled -> SetupReadiness.ManualChoice.ENABLED
                R.id.optionBatteryUnavailable -> SetupReadiness.ManualChoice.UNAVAILABLE
                else -> SetupReadiness.ManualChoice.PENDING
            }
            SetupReadiness.chooseBattery(this, choice)
            refresh()
        }
        binding.groupAutostart.setOnCheckedChangeListener { _, id ->
            val choice = when (id) {
                R.id.optionAutostartEnabled -> SetupReadiness.ManualChoice.ENABLED
                R.id.optionAutostartUnavailable -> SetupReadiness.ManualChoice.UNAVAILABLE
                else -> SetupReadiness.ManualChoice.PENDING
            }
            SetupReadiness.chooseAutostart(this, choice)
            refresh()
        }
        binding.buttonVerify.setOnClickListener { refresh() }
        binding.buttonContinue.setOnClickListener {
            if (SetupReadiness.status(this).ready) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                refresh()
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        SetupGuideOverlay.dismiss()
        if (::binding.isInitialized) refresh()
    }

    override fun onDestroy() {
        testExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupPreferencePickers() {
        val settings = AppSettingsStore(this)
        val languages = Languages.ALL
        binding.spinnerSetupLanguage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, languages.map { it.pickerLabel() }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val initialLanguageIndex = languages.indexOfFirst {
            it.code == (settings.appInterfaceLanguage ?: settings.bubbleTargetLanguage.code)
        }.coerceAtLeast(0)
        binding.spinnerSetupLanguage.setSelection(initialLanguageIndex, false)
        var awaitingInitialSelection = true
        binding.spinnerSetupLanguage.post {
            binding.spinnerSetupLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (awaitingInitialSelection) {
                        awaitingInitialSelection = false
                        if (position == initialLanguageIndex) return
                    }
                    val selected = languages.getOrNull(position) ?: return
                    if (selected.code == settings.bubbleTargetLanguage.code &&
                        selected.code == (settings.appInterfaceLanguage ?: Languages.DEFAULT.code)) return
                    settings.bubbleTargetLanguage = TriggerDetector.Target(selected.code)
                    settings.appInterfaceLanguage = selected.code
                    recreate()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val genders = UserGender.entries
        binding.spinnerSetupGender.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, genders.map { gender ->
                getString(when (gender) {
                    UserGender.AUTO -> R.string.label_gender_auto
                    UserGender.MALE -> R.string.label_gender_male
                    UserGender.FEMALE -> R.string.label_gender_female
                })
            }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerSetupGender.setSelection(genders.indexOf(settings.userGender).coerceAtLeast(0), false)
        binding.spinnerSetupGender.post {
            binding.spinnerSetupGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    genders.getOrNull(position)?.let { settings.userGender = it }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun refresh() {
        val status = SetupReadiness.status(this)
        binding.textKeyStatus.setText(when {
            status.apiTestPassed -> R.string.setup_done
            status.apiKeyReady -> R.string.setup_key_saved
            else -> R.string.setup_key_missing
        })
        binding.textKeyStatus.setTextColor(getColor(if (status.apiTestPassed) R.color.tg_primary else R.color.tg_warning))
        binding.textKeyHelp.visibility = if (status.apiKeyReady) View.GONE else View.VISIBLE
        binding.buttonGetApiKey.visibility = if (status.apiKeyReady) View.GONE else View.VISIBLE
        binding.editApiKey.visibility = if (status.apiKeyReady) View.GONE else View.VISIBLE
        binding.buttonSaveApiKey.visibility = if (status.apiKeyReady) View.GONE else View.VISIBLE
        binding.buttonTestApi.visibility = if (status.apiKeyReady) View.VISIBLE else View.GONE
        binding.textAccessibilityStatus.setText(if (status.accessibilityEnabled) R.string.setup_done else R.string.setup_accessibility_missing)
        binding.textAccessibilityStatus.setTextColor(getColor(if (status.accessibilityEnabled) R.color.tg_primary else R.color.tg_warning))
        binding.textAccessibilityHelp.visibility = if (status.accessibilityEnabled) View.GONE else View.VISIBLE
        binding.buttonAccessibility.visibility = if (status.accessibilityEnabled) View.GONE else View.VISIBLE
        binding.textTriggerStatus.setText(if (status.triggerEnabled) R.string.setup_done else R.string.setup_trigger_missing)
        binding.textTriggerStatus.setTextColor(getColor(if (status.triggerEnabled) R.color.tg_primary else R.color.tg_warning))
        binding.buttonEnableTrigger.visibility = if (status.triggerEnabled) View.GONE else View.VISIBLE
        val batteryId = when (status.batteryChoice) {
            SetupReadiness.ManualChoice.ENABLED -> R.id.optionBatteryEnabled
            SetupReadiness.ManualChoice.UNAVAILABLE -> R.id.optionBatteryUnavailable
            SetupReadiness.ManualChoice.PENDING -> -1
        }
        if (binding.groupBattery.checkedRadioButtonId != batteryId) binding.groupBattery.check(batteryId)
        val autostartId = when (status.autostartChoice) {
            SetupReadiness.ManualChoice.ENABLED -> R.id.optionAutostartEnabled
            SetupReadiness.ManualChoice.UNAVAILABLE -> R.id.optionAutostartUnavailable
            SetupReadiness.ManualChoice.PENDING -> -1
        }
        if (binding.groupAutostart.checkedRadioButtonId != autostartId) binding.groupAutostart.check(autostartId)
        binding.textSummary.setText(if (status.ready) R.string.setup_ready else R.string.setup_incomplete)
        binding.textSummary.setTextColor(getColor(if (status.ready) R.color.tg_primary else R.color.tg_text_secondary))
        binding.buttonContinue.isEnabled = status.ready
    }

    private fun showOpenError() = Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
