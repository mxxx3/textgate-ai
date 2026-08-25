package com.textgate.ai.translate

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import com.textgate.ai.LocaleHelper
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.databinding.ContentTranslateBinding
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.model.TranslationPrompts
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.network.ModelAvailabilityStore
import com.textgate.ai.network.TranslationOrchestrator
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import com.textgate.ai.security.TriggerDetector
import com.textgate.ai.util.Debouncer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Drives the "Tłumacz" tab (content_translate.xml) — a manual,
 * Google-Translate-style equivalent of the existing typed-trigger pipeline
 * ([com.textgate.ai.accessibility.TextGateAccessibilityService.
 * confirmAndProcess]), reusing the exact same translation call
 * ([TranslationOrchestrator]) and system prompt
 * ([TranslationPrompts.systemPromptFor] with the user's gender + preferred
 * language, exactly as the typed-trigger path already does — this screen
 * translates text the user is typing themselves, the same shape of request
 * as a `?xx` trigger, so it is conditioned the same way). Entirely
 * independent of the `?xx` trigger mechanism and the long-press bubble —
 * neither is touched by this controller.
 */
class TranslateTabController(
    private val activity: Activity,
    private val binding: ContentTranslateBinding
) {

    private val settingsStore = AppSettingsStore(activity.applicationContext)
    private val apiKeyStore = SecureApiKeyStore(activity.applicationContext)
    private val availabilityStore = ModelAvailabilityStore(activity.applicationContext)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val debouncer = Debouncer(TRANSLATE_DEBOUNCE_MS)
    private var executor: ExecutorService? = Executors.newSingleThreadExecutor()

    /** index 0 is "Automatycznie" (null); the rest mirror [Languages.ALL]. */
    private val sourceLanguageValues: List<SupportedLanguage?> = listOf(null) + Languages.ALL
    private val targetLanguageValues: List<SupportedLanguage> = Languages.ALL

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    init {
        setupLanguageSpinners()
        setupTextWatcher()
        setupButtons()
        textToSpeech = TextToSpeech(activity.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    fun onDestroy() {
        debouncer.cancel()
        executor?.shutdownNow()
        executor = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun setupLanguageSpinners() {
        val sourceLabels = listOf(activity.getString(R.string.label_language_auto)) +
            Languages.ALL.map { it.nativeName }
        val sourceAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, sourceLabels)
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSourceLanguage.adapter = sourceAdapter
        binding.spinnerSourceLanguage.setSelection(0, false)

        val targetLabels = Languages.ALL.map { it.nativeName }
        val targetAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, targetLabels)
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTargetLanguage.adapter = targetAdapter
        val defaultTargetIndex = targetLanguageValues.indexOfFirst {
            it.code == settingsStore.bubbleTargetLanguage.code
        }.let { if (it >= 0) it else 0 }
        binding.spinnerTargetLanguage.setSelection(defaultTargetIndex, false)

        binding.buttonSwapLanguages.setOnClickListener { swapLanguages() }
    }

    private fun swapLanguages() {
        val sourceIndex = binding.spinnerSourceLanguage.selectedItemPosition
        val currentSource = sourceLanguageValues.getOrNull(sourceIndex)
        if (currentSource == null) {
            Toast.makeText(activity, R.string.translate_swap_needs_source, Toast.LENGTH_SHORT).show()
            return
        }
        val targetIndex = binding.spinnerTargetLanguage.selectedItemPosition
        val currentTarget = targetLanguageValues.getOrNull(targetIndex) ?: return

        val newSourceIndex = sourceLanguageValues.indexOfFirst { it?.code == currentTarget.code }
        val newTargetIndex = targetLanguageValues.indexOfFirst { it.code == currentSource.code }
        if (newSourceIndex >= 0) binding.spinnerSourceLanguage.setSelection(newSourceIndex)
        if (newTargetIndex >= 0) binding.spinnerTargetLanguage.setSelection(newTargetIndex)

        val sourceText = binding.editSourceText.text?.toString().orEmpty()
        val resultText = binding.textTranslationResult.text?.toString().orEmpty()
        if (resultText.isNotBlank()) {
            binding.editSourceText.setText(resultText)
            binding.editSourceText.setSelection(resultText.length)
        } else if (sourceText.isNotBlank()) {
            scheduleTranslate()
        }
    }

    private fun setupTextWatcher() {
        binding.editSourceText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isBlank()) {
                    debouncer.cancel()
                    binding.textTranslationResult.text = ""
                    return
                }
                scheduleTranslate()
            }
        })
    }

    private fun setupButtons() {
        binding.buttonClearSource.setOnClickListener {
            debouncer.cancel()
            binding.editSourceText.text?.clear()
            binding.textTranslationResult.text = ""
        }
        binding.buttonCopyResult.setOnClickListener {
            val text = binding.textTranslationResult.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.app_name), text))
            Toast.makeText(activity, R.string.translate_copied, Toast.LENGTH_SHORT).show()
        }
        binding.buttonSpeakResult.setOnClickListener { speakResult() }
        binding.buttonMicInput.setOnClickListener { startMicInput() }
    }

    // ---------------------------------------------------------------
    // Translation
    // ---------------------------------------------------------------

    private fun scheduleTranslate() {
        debouncer.schedule { runTranslate() }
    }

    private fun runTranslate() {
        val sourceText = binding.editSourceText.text?.toString().orEmpty()
        if (sourceText.isBlank()) return

        val targetIndex = binding.spinnerTargetLanguage.selectedItemPosition
        val targetLanguage = targetLanguageValues.getOrNull(targetIndex) ?: Languages.DEFAULT

        if (!apiKeyStore.hasAnyKey()) {
            binding.textTranslationResult.text = activity.getString(R.string.error_no_api_key)
            return
        }

        val model = settingsStore.selectedModel
        val target = TriggerDetector.Target(targetLanguage.code)
        val userPreferredLanguage = LocaleHelper.resolvePreferredLanguage(activity.applicationContext)
        val systemPrompt = TranslationPrompts.systemPromptFor(
            target = target,
            speakerGender = settingsStore.userGender,
            userPreferredLanguage = userPreferredLanguage
        )

        val exec = executor ?: return
        binding.progressTranslating.visibility = View.VISIBLE
        exec.execute {
            val result = try {
                TranslationOrchestrator.translateText(
                    apiKeyStore = apiKeyStore,
                    availabilityStore = availabilityStore,
                    requestedModel = model,
                    systemPrompt = systemPrompt,
                    userText = sourceText
                )
            } catch (_: Exception) {
                GeminiClient.Result.Failure.InvalidResponse
            }
            mainHandler.post {
                binding.progressTranslating.visibility = View.GONE
                // The source field may have changed while this request was
                // in flight — only apply a result that still matches what
                // is currently on screen, same "don't overwrite a moving
                // target" discipline TextGateAccessibilityService uses.
                if (binding.editSourceText.text?.toString().orEmpty() != sourceText) return@post
                when (result) {
                    is GeminiClient.Result.Success -> binding.textTranslationResult.text = result.translatedText
                    is GeminiClient.Result.Failure -> binding.textTranslationResult.text =
                        describeFailure(result)
                }
            }
        }
    }

    private fun describeFailure(failure: GeminiClient.Result.Failure): String = when (failure) {
        GeminiClient.Result.Failure.Timeout -> activity.getString(R.string.error_timeout)
        GeminiClient.Result.Failure.NetworkError -> activity.getString(R.string.error_network)
        is GeminiClient.Result.Failure.HttpError -> activity.getString(R.string.error_http, failure.code)
        GeminiClient.Result.Failure.EmptyResponse -> activity.getString(R.string.error_empty_response)
        GeminiClient.Result.Failure.MissingApiKey -> activity.getString(R.string.error_no_api_key)
        is GeminiClient.Result.Failure.AllKeysExhausted -> activity.getString(R.string.error_all_keys_exhausted)
        is GeminiClient.Result.Failure.QuotaExceeded -> activity.getString(R.string.error_quota_exceeded)
        GeminiClient.Result.Failure.InvalidModel,
        GeminiClient.Result.Failure.InvalidResponse,
        GeminiClient.Result.Failure.HostNotAllowed -> activity.getString(R.string.error_generic)
    }

    // ---------------------------------------------------------------
    // One-shot mic dictation (platform SpeechRecognizer — no library)
    // ---------------------------------------------------------------

    private fun startMicInput() {
        if (!MainActivity.hasRecordAudioPermission(activity)) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MainActivity.PERMISSION_REQUEST_RECORD_AUDIO_TRANSLATE
            )
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, R.string.translate_mic_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val sourceIndex = binding.spinnerSourceLanguage.selectedItemPosition
        val sourceLanguage = sourceLanguageValues.getOrNull(sourceIndex)
        val recognizerLocale = sourceLanguage?.localeLanguageTag ?: Locale.getDefault().toLanguageTag()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    binding.editSourceText.setText(text)
                    binding.editSourceText.setSelection(text.length)
                    scheduleTranslate()
                }
                recognizer.destroy()
                if (speechRecognizer === recognizer) speechRecognizer = null
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                if (speechRecognizer === recognizer) speechRecognizer = null
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLocale)
        }
        recognizer.startListening(intent)
    }

    /** Called from [MainActivity.onRequestPermissionsResult] — retries the
     * mic action once the user grants RECORD_AUDIO from the system prompt
     * this controller itself triggered. */
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != MainActivity.PERMISSION_REQUEST_RECORD_AUDIO_TRANSLATE) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMicInput()
        }
    }

    // ---------------------------------------------------------------
    // TTS playback of the translation result
    // ---------------------------------------------------------------

    private fun speakResult() {
        val text = binding.textTranslationResult.text?.toString().orEmpty()
        if (text.isBlank() || !ttsReady) return
        val targetIndex = binding.spinnerTargetLanguage.selectedItemPosition
        val targetLanguage = targetLanguageValues.getOrNull(targetIndex) ?: Languages.DEFAULT
        textToSpeech?.language = Locale.forLanguageTag(targetLanguage.localeLanguageTag)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "textgate_translate_result")
    }

    companion object {
        private const val TRANSLATE_DEBOUNCE_MS = 700L
    }
}
