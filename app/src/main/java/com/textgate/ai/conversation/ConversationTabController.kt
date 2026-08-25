package com.textgate.ai.conversation

import android.Manifest
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.widget.ArrayAdapter
import android.widget.Toast
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.databinding.ContentConversationBinding
import com.textgate.ai.live.GeminiLiveClient
import com.textgate.ai.live.GeminiLiveClient.ServerEvent
import com.textgate.ai.live.LiveTranslationService
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.security.SecureApiKeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the "Rozmowa" tab — a two-person, in-person conversation mode
 * using [GeminiLiveClient] directly for `gemini-3.5-live-translate-preview`
 * native audio-to-audio translation (never a text model — see that
 * client's own class doc for why the two model roles must stay
 * independent).
 *
 * Deliberately foreground-only, NOT backed by a Foreground Service the way
 * [com.textgate.ai.live.LiveTranslationService] (Na żywo) is: a two-person
 * conversation is, by its nature, a session both participants are actively
 * present for and looking at the screen during — unlike Na żywo's ambient
 * "phone in your pocket, screen off" use case, there is no scenario the
 * spec asks this mode to survive backgrounding for. [onStop] always ends
 * an active session; reopening the app never resumes one.
 *
 * The Gemini Live API's `translationConfig` carries exactly ONE
 * `targetLanguageCode` per session (see [GeminiLiveClient]'s wire-format
 * note) — there is no per-utterance "translate the other direction this
 * time" switch documented. This controller models a two-person
 * conversation as ONE active translation DIRECTION at a time (Language A
 * → Language B, or the reverse), shown in [ContentConversationBinding.
 * textConversationDirection], with [ContentConversationBinding.
 * buttonSwapDirection] closing and reopening the Live session with the
 * other target language when the conversation's speaker changes. This is
 * a deliberate simplification, not a misunderstanding of the two-person
 * requirement — see this project's final summary for why: without a
 * confirmed way to change `targetLanguageCode` on an already-open session,
 * reconnecting is the only reliable option available.
 */
class ConversationTabController(
    private val activity: Activity,
    private val binding: ContentConversationBinding
) {

    private val apiKeyStore = SecureApiKeyStore(activity.applicationContext)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val languages: List<SupportedLanguage> = Languages.ALL
    private var languageA: SupportedLanguage = Languages.DEFAULT
    private var languageB: SupportedLanguage = Languages.byCode("en") ?: Languages.DEFAULT
    /** true: translating A's speech into B; false: the reverse. */
    private var directionAtoB = true

    private var liveClient: GeminiLiveClient? = null
    private var captureThread: Thread? = null
    private var playbackTrack: AudioTrack? = null
    private val micActive = AtomicBoolean(false)
    private var connecting = false

    init {
        setupLanguageSpinners()
        updateDirectionLabel()
        binding.buttonSwapDirection.setOnClickListener { onSwapDirection() }
        binding.buttonConversationStartStop.setOnClickListener { onStartStopClicked() }
    }

    fun onStop() {
        // Foreground-only, per this class's doc — always tear down when
        // the Activity leaves the foreground.
        stopSession()
    }

    fun onDestroy() {
        stopSession()
    }

    private fun setupLanguageSpinners() {
        val labels = languages.map { it.nativeName }
        val adapterA = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
        adapterA.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguageA.adapter = adapterA
        binding.spinnerLanguageA.setSelection(languages.indexOfFirst { it.code == languageA.code }.coerceAtLeast(0))

        val adapterB = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
        adapterB.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguageB.adapter = adapterB
        binding.spinnerLanguageB.setSelection(languages.indexOfFirst { it.code == languageB.code }.coerceAtLeast(0))

        val listener = { _: Any? ->
            languageA = languages.getOrNull(binding.spinnerLanguageA.selectedItemPosition) ?: languageA
            languageB = languages.getOrNull(binding.spinnerLanguageB.selectedItemPosition) ?: languageB
            updateDirectionLabel()
            if (liveClient != null) restartSessionWithCurrentDirection()
        }
        binding.spinnerLanguageA.post {
            binding.spinnerLanguageA.onItemSelectedListener = simpleSelectionListener { listener(null) }
            binding.spinnerLanguageB.onItemSelectedListener = simpleSelectionListener { listener(null) }
        }
    }

    private fun simpleSelectionListener(onSelected: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) =
                onSelected()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun onSwapDirection() {
        directionAtoB = !directionAtoB
        updateDirectionLabel()
        if (liveClient != null) restartSessionWithCurrentDirection()
    }

    private fun updateDirectionLabel() {
        val from = if (directionAtoB) languageA else languageB
        val to = if (directionAtoB) languageB else languageA
        binding.textConversationDirection.text =
            activity.getString(R.string.conversation_direction_format, from.nativeName, to.nativeName)
    }

    private fun currentTargetLanguage(): SupportedLanguage = if (directionAtoB) languageB else languageA

    // ---------------------------------------------------------------
    // Session control
    // ---------------------------------------------------------------

    private fun onStartStopClicked() {
        if (liveClient != null || connecting) {
            stopSession()
        } else {
            startSession()
        }
    }

    private fun restartSessionWithCurrentDirection() {
        stopSession()
        startSession()
    }

    private fun startSession() {
        if (!MainActivity.hasRecordAudioPermission(activity)) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MainActivity.PERMISSION_REQUEST_RECORD_AUDIO_CONVERSATION
            )
            return
        }
        val apiKey = apiKeyStore.getActiveKeyPlaintext()
        if (apiKey == null) {
            Toast.makeText(activity, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        connecting = true
        binding.textConversationStatus.text = activity.getString(R.string.live_state_connecting)
        binding.buttonConversationStartStop.setText(R.string.conversation_button_stop)

        val client = GeminiLiveClient()
        liveClient = client
        client.connect(
            apiKey = apiKey,
            model = LiveTranslationService.LIVE_MODEL,
            targetLanguageCode = currentTargetLanguage().localeLanguageTag
        ) { event -> mainHandler.post { handleServerEvent(event) } }
    }

    private fun handleServerEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.SetupComplete -> {
                connecting = false
                beginCapturePlayback()
                binding.textConversationStatus.text = activity.getString(R.string.live_state_listening)
            }
            is ServerEvent.InputTranscript ->
                binding.textConversationInputTranscript.text = event.text
            is ServerEvent.OutputTranscript ->
                binding.textConversationOutputTranscript.text = event.text
            is ServerEvent.AudioChunk -> {
                binding.textConversationStatus.text = activity.getString(R.string.live_state_translating)
                playbackTrack?.write(event.pcm16, 0, event.pcm16.size)
            }
            is ServerEvent.TurnComplete ->
                binding.textConversationStatus.text = activity.getString(R.string.live_state_listening)
            is ServerEvent.Error -> {
                Toast.makeText(activity, activity.getString(R.string.live_notification_error) + ": " + event.message, Toast.LENGTH_LONG).show()
                stopSession()
            }
            is ServerEvent.Closed -> if (event.code != 1000) stopSession()
        }
    }

    private fun stopSession() {
        connecting = false
        micActive.set(false)
        captureThread?.let { try { it.join(300) } catch (_: InterruptedException) { } }
        captureThread = null
        playbackTrack?.let { try { it.stop(); it.release() } catch (_: Exception) { } }
        playbackTrack = null
        liveClient?.close()
        liveClient = null
        binding.textConversationStatus.text = activity.getString(R.string.live_state_stopped)
        binding.buttonConversationStartStop.setText(R.string.conversation_button_start)
    }

    private fun beginCapturePlayback() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(MIN_BUFFER_FLOOR)

        playbackTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        playbackTrack?.play()

        micActive.set(true)
        val thread = Thread({ runCaptureLoop(minBufferSize) }, "TextGateConversationCapture")
        captureThread = thread
        thread.start()
    }

    @Suppress("MissingPermission") // RECORD_AUDIO is checked in startSession() before this capture thread is ever started.
    private fun runCaptureLoop(bufferSize: Int) {
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                CAPTURE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: Exception) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        try {
            record.startRecording()
            val buffer = ByteArray(bufferSize)
            while (micActive.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    liveClient?.sendAudioChunk(chunk)
                }
            }
        } catch (_: Exception) {
            // Capture loop winding down — nothing further to do.
        } finally {
            try { record.stop() } catch (_: Exception) { }
            record.release()
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != MainActivity.PERMISSION_REQUEST_RECORD_AUDIO_CONVERSATION) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startSession()
        }
    }

    companion object {
        private const val CAPTURE_SAMPLE_RATE_HZ = 16_000
        private const val PLAYBACK_SAMPLE_RATE_HZ = 24_000
        private const val MIN_BUFFER_FLOOR = 3_200
    }
}
