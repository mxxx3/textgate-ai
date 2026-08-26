package com.textgate.ai.conversation

import android.Manifest
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.Toast
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.databinding.ContentConversationBinding
import com.textgate.ai.live.GeminiLiveClient
import com.textgate.ai.live.GeminiLiveClient.ServerEvent
import com.textgate.ai.live.LiveTranslationService
import com.textgate.ai.model.AudioCaptureMode
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.security.AppSettingsStore
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
    private val settingsStore = AppSettingsStore(activity.applicationContext)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

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

        // playbackTrack (built in beginCapturePlayback, below) uses
        // AudioAttributes.USAGE_VOICE_COMMUNICATION in ECHO_CANCELLED mode
        // (the default — see AudioCaptureMode's class doc), which the
        // platform maps to the legacy STREAM_VOICE_CALL stream, or
        // USAGE_MEDIA in STANDARD mode, which maps to STREAM_MUSIC — either
        // way the hardware volume keys only follow that mapping if this
        // Activity says so; left at the default they'd silently adjust
        // whichever stream isn't actually in use, so vol+/- would appear to
        // do nothing. No proximity/screen-off wake lock here on purpose,
        // unlike Na żywo: Rozmowa is a face-to-face mode both people look at
        // the screen during (see this class's own doc), not a to-the-ear
        // call.
        activity.volumeControlStream = if (settingsStore.audioCaptureMode == AudioCaptureMode.ECHO_CANCELLED) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.STREAM_MUSIC
        }

        val client = GeminiLiveClient()
        liveClient = client
        client.connect(
            apiKey = apiKey,
            model = LiveTranslationService.LIVE_MODEL,
            targetLanguageCode = currentTargetLanguage().localeLanguageTag
        ) { event ->
            mainHandler.post {
                // See LiveTranslationService.startSession's identical guard
                // for the full reasoning: GeminiLiveClient.close() tears the
                // socket down asynchronously, so a stale event from a
                // superseded/closed [client] can still arrive after
                // stopSession() or restartSessionWithCurrentDirection()
                // already moved on to null or a newer client — without this
                // check it would run handleServerEvent against whatever
                // session is CURRENT instead of being silently dropped.
                if (liveClient !== client) return@post
                handleServerEvent(event)
            }
        }
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
        activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        // Symmetric with beginCapturePlayback's MODE_IN_COMMUNICATION and
        // applyCommunicationRouting below.
        resetCommunicationRouting()
        audioManager.mode = AudioManager.MODE_NORMAL
        binding.textConversationStatus.text = activity.getString(R.string.live_state_stopped)
        binding.buttonConversationStartStop.setText(R.string.conversation_button_start)
    }

    private fun beginCapturePlayback() {
        // Which capture/playback path to use is a user setting as of
        // v2.0.1 — see AudioCaptureMode's own class doc for the full story
        // and LiveTranslationService.beginCapturePlayback's identical
        // branch for the twin implementation.
        val captureMode = settingsStore.audioCaptureMode
        if (captureMode == AudioCaptureMode.ECHO_CANCELLED) {
            // MODE_IN_COMMUNICATION, paired with runCaptureLoop's
            // AudioSource.VOICE_COMMUNICATION and this method's own
            // AudioAttributes.USAGE_VOICE_COMMUNICATION below — see
            // LiveTranslationService.beginCapturePlayback's identical
            // comment for the full reasoning: without this,
            // USAGE_VOICE_COMMUNICATION *playback* can be routed/attenuated
            // incorrectly on some devices even though capture and
            // AudioTrack.write() both appear to work, which looks exactly
            // like "status says translating, but silence."
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // MODE_IN_COMMUNICATION alone does not guarantee loudspeaker
            // routing — see LiveTranslationService.applyCommunicationRouting's
            // doc comment for the full, sourced reasoning (a 2026 Android
            // audio-routing writeup: without setCommunicationDevice(), the
            // system may silently default to the earpiece path, which is
            // tuned for a mouth pressed against the top of the phone and
            // barely picks up anything else). Rozmowa always forces the
            // speaker, unconditionally, in this mode — unlike Na żywo it has
            // no headset concept at all (see this class's own doc: it's a
            // face-to-face, loudspeaker mode by design).
            applyCommunicationRouting()
        }
        // STANDARD: deliberately leave AudioManager.mode and device routing
        // untouched — see AudioCaptureMode's doc for why.

        val minBufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(MIN_BUFFER_FLOOR)

        playbackTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (captureMode == AudioCaptureMode.ECHO_CANCELLED) {
                            AudioAttributes.USAGE_VOICE_COMMUNICATION
                        } else {
                            AudioAttributes.USAGE_MEDIA
                        }
                    )
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
        val thread = Thread({ runCaptureLoop(minBufferSize, captureMode) }, "TextGateConversationCapture")
        captureThread = thread
        thread.start()
    }

    /** See [beginCapturePlayback]'s call site for the full reasoning; kept
     * as a near-identical twin of LiveTranslationService's version of the
     * same fix rather than shared, since the two controllers have no common
     * base to share it from and each has its own [audioManager] instance.
     * Always forces the speaker here — Rozmowa has no headset branch. */
    private fun applyCommunicationRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speakerDevice = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            try {
                if (speakerDevice != null) audioManager.setCommunicationDevice(speakerDevice)
            } catch (_: Exception) {
                // Best-effort only.
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            } catch (_: Exception) {
                // Best-effort only.
            }
        }
    }

    private fun resetCommunicationRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {
                // Best-effort only.
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            } catch (_: Exception) {
                // Best-effort only.
            }
        }
    }

    @Suppress("MissingPermission") // RECORD_AUDIO is checked in startSession() before this capture thread is ever started.
    private fun runCaptureLoop(bufferSize: Int, captureMode: AudioCaptureMode) {
        val record = try {
            AudioRecord(
                // VOICE_COMMUNICATION in ECHO_CANCELLED mode, plain MIC in
                // STANDARD mode — see AudioCaptureMode's class doc and
                // LiveTranslationService's own runCaptureLoop for the full
                // reasoning: this screen plays translated audio out loud
                // while simultaneously capturing, and AudioSource.MIC has no
                // echo handling, so on a speaker (no headset) the mic hears
                // the phone's own translated output and sends it back as
                // "new speech" — a real, reported bug ("hears one sentence,
                // then loops"). VOICE_COMMUNICATION routes through the
                // platform's telephony audio pipeline, which applies
                // acoustic echo cancellation (AEC) where the device supports
                // it — at the cost, per a later report, of extra latency and
                // occasionally cut-off turns on some devices.
                if (captureMode == AudioCaptureMode.ECHO_CANCELLED) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.MIC
                },
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
        // Explicit AEC on top of the VOICE_COMMUNICATION source — see
        // LiveTranslationService's runCaptureLoop for why this is kept as a
        // best-effort, never-fatal addition rather than relied on alone.
        // Only attempted in ECHO_CANCELLED mode.
        val echoCanceler = if (captureMode == AudioCaptureMode.ECHO_CANCELLED) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
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
            try { echoCanceler?.release() } catch (_: Exception) { }
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
