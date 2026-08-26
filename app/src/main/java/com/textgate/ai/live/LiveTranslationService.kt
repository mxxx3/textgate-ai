package com.textgate.ai.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.textgate.ai.LocaleHelper
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.live.GeminiLiveClient.ServerEvent
import com.textgate.ai.model.HeadsetDisconnectBehavior
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the ENTIRE lifecycle of one ambient "Na żywo" Live translation
 * session — microphone capture, the [GeminiLiveClient] WebSocket session,
 * translated-audio playback, audio focus, reconnect/backoff, audio-route
 * (headset) handling, and the conditional [android.os.PowerManager.
 * PARTIAL_WAKE_LOCK] — as a Foreground Service, so all of it keeps working
 * through screen-off, lock, backgrounding, and the hosting Activity being
 * destroyed and recreated (e.g. on rotation). The Live connection belongs
 * HERE, never to an Activity; reopening the app while a session is active
 * must bind to this already-running service and reflect its real state,
 * never start a second parallel session (see [onStartCommand] — a second
 * ACTION_START while already active/connecting is a no-op).
 *
 * STARTED ONLY VIA EXPLICIT USER ACTION. This service is started with
 * [ACTION_START] from exactly one place — the Na żywo screen's START
 * button, while the app is in the foreground (a hard Android 12+
 * requirement for starting a foreground service from the background
 * anyway) — never from app launch, never from boot, never silently. See
 * README.md's Live section for the full privacy reasoning: the user must
 * always unambiguously know the mic is active, via this service's own
 * persistent notification, the Na żywo screen itself, and Android's own
 * system mic-in-use indicator (a platform behavior this service does
 * nothing to suppress).
 *
 * Deliberately does NOT use [android.view.WindowManager.LayoutParams.
 * FLAG_KEEP_SCREEN_ON] or any equivalent — the screen is allowed to turn
 * off normally. The [PowerManager.PARTIAL_WAKE_LOCK] acquired in
 * [transitionTo] keeps the CPU (not the screen) available for mic capture
 * and network I/O while the screen is off; it is always released on STOP,
 * on an unrecoverable [LiveSessionState.ERROR], and in [onDestroy] — see
 * [releaseWakeLock].
 */
class LiveTranslationService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LiveTranslationService = this@LiveTranslationService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settingsStore: AppSettingsStore
    private lateinit var apiKeyStore: SecureApiKeyStore
    private lateinit var audioRouteMonitor: AudioRouteMonitor
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var liveClient: GeminiLiveClient? = null
    private var captureThread: Thread? = null
    private var playbackTrack: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val micActive = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    var state: LiveSessionState = LiveSessionState.STOPPED
        private set
    var sourceLanguage: SupportedLanguage? = null // null = "Automatycznie" (ambient auto-detect)
        private set
    var targetLanguage: SupportedLanguage = Languages.DEFAULT
        private set
    var latestInputTranscript: String = ""
        private set
    var latestOutputTranscript: String = ""
        private set

    private val stateListeners = mutableListOf<(LiveSessionState) -> Unit>()

    fun addStateListener(listener: (LiveSessionState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    fun removeStateListener(listener: (LiveSessionState) -> Unit) {
        stateListeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = AppSettingsStore(applicationContext)
        apiKeyStore = SecureApiKeyStore(applicationContext)
        audioRouteMonitor = AudioRouteMonitor(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground() immediately, on every start command,
        // regardless of what happens next — this is a hard platform
        // requirement once startForegroundService() was used to launch us,
        // and it is also this session's own visible "the mic may be active"
        // signal, per this class's privacy doc above.
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> {
                if (state != LiveSessionState.STOPPED && state != LiveSessionState.ERROR) {
                    // Already connecting/active — never run two parallel
                    // sessions (see class doc).
                    return START_NOT_STICKY
                }
                val sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANGUAGE_CODE)
                sourceLanguage = sourceCode?.let { Languages.byCode(it) }
                targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE_CODE)
                    ?.let { Languages.byCode(it) }
                    ?: LocaleHelper.resolvePreferredLanguage(applicationContext)
                reconnectAttempt = 0
                startSession()
            }
            ACTION_STOP -> stopSession(userInitiated = true)
            ACTION_RETRY -> {
                reconnectAttempt = 0
                startSession()
            }
        }
        // Not STICKY: if the system kills this process under memory
        // pressure, a silently-resurrected mic session would violate the
        // "only ever active after a deliberate START" guarantee — the user
        // must press START again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession(userInitiated = false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Session lifecycle
    // ---------------------------------------------------------------

    private fun startSession() {
        transitionTo(LiveSessionState.CONNECTING)
        // Acquired right after START (per spec), not only once the mic
        // actually starts — the risk window between pressing START and the
        // Live session finishing its handshake must be covered too,
        // especially if the screen turns off in that window. Always
        // released on STOP/ERROR/onDestroy — see releaseWakeLock.
        acquireWakeLock()

        val apiKey = apiKeyStore.getActiveKeyPlaintext()
        if (apiKey == null) {
            releaseWakeLock()
            transitionTo(LiveSessionState.ERROR)
            updateNotification()
            return
        }

        audioRouteMonitor.start { hasPrivateRoute -> mainHandler.post { onRouteChanged(hasPrivateRoute) } }

        val client = GeminiLiveClient()
        liveClient = client
        client.connect(
            apiKey = apiKey,
            model = LIVE_MODEL,
            targetLanguageCode = targetLanguage.localeLanguageTag
        ) { event ->
            mainHandler.post {
                // GeminiLiveClient.close() (called from stopSession/
                // handleDisconnect) tears the socket down asynchronously —
                // OkHttp's onClosed/onFailure, or this class's own 20s setup
                // watchdog, can still fire and post an event AFTER [client]
                // has already been replaced by a newer session (a quick
                // stop-then-restart) or set to null (a plain stop). Without
                // this identity check, that stale event would run
                // handleServerEvent against whatever session is CURRENT —
                // e.g. calling handleDisconnect() and tearing down a
                // brand-new session because the OLD one finally reported an
                // error, or reposting the notification with stale state
                // right after stopSession() already removed it. Two real
                // reports traced to exactly this: the notification not
                // disappearing after STOP, and a restart soon after a stop
                // appearing to connect but never actually translating.
                if (liveClient !== client) return@post
                handleServerEvent(event)
            }
        }
    }

    private fun handleServerEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.SetupComplete -> {
                reconnectAttempt = 0
                if (audioRouteMonitor.hasPrivateOutputRoute() ||
                    settingsStore.headsetDisconnectBehavior == HeadsetDisconnectBehavior.SWITCH_TO_SPEAKER
                ) {
                    beginCapturePlayback()
                } else {
                    // No headset and the user has NOT opted into speaker
                    // playback — start paused rather than ever letting a
                    // translation play out loud without the user's
                    // explicit "switch to speaker" choice.
                    transitionTo(LiveSessionState.PAUSED)
                }
            }
            is ServerEvent.InputTranscript -> {
                latestInputTranscript = event.text
                notifyListeners()
            }
            is ServerEvent.OutputTranscript -> {
                latestOutputTranscript = event.text
                notifyListeners()
            }
            is ServerEvent.AudioChunk -> {
                if (state == LiveSessionState.LISTENING) transitionTo(LiveSessionState.TRANSLATING)
                playbackTrack?.write(event.pcm16, 0, event.pcm16.size)
            }
            is ServerEvent.TurnComplete -> {
                if (state == LiveSessionState.TRANSLATING) transitionTo(LiveSessionState.LISTENING)
            }
            is ServerEvent.Error -> handleDisconnect()
            is ServerEvent.Closed -> if (event.code != 1000) handleDisconnect()
        }
        updateNotification()
    }

    private fun handleDisconnect() {
        stopCapturePlayback()
        liveClient?.close()
        liveClient = null

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            releaseWakeLock()
            releaseAudioFocus()
            audioRouteMonitor.stop()
            transitionTo(LiveSessionState.ERROR)
            return
        }

        transitionTo(LiveSessionState.RECONNECTING)
        val delayMs = RECONNECT_BACKOFF_MS.getOrElse(reconnectAttempt) { RECONNECT_BACKOFF_MS.last() }
        reconnectAttempt++
        val runnable = Runnable { startSession() }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun stopSession(userInitiated: Boolean) {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        stopCapturePlayback()
        liveClient?.close()
        liveClient = null
        audioRouteMonitor.stop()
        releaseAudioFocus()
        releaseWakeLock()
        latestInputTranscript = ""
        latestOutputTranscript = ""
        transitionTo(LiveSessionState.STOPPED)
        if (userInitiated) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------
    // Headset / audio route
    // ---------------------------------------------------------------

    private fun onRouteChanged(hasPrivateRoute: Boolean) {
        if (hasPrivateRoute) {
            // Headset (re)connected: resume automatically if we were
            // paused waiting for exactly this — see HeadsetDisconnectBehavior's
            // class doc for why PAUSE_TRANSLATION never auto-plays on the
            // speaker, but DOES auto-resume the moment a private route
            // returns.
            if (state == LiveSessionState.PAUSED && liveClient != null) {
                beginCapturePlayback()
            }
            return
        }

        if (settingsStore.headsetDisconnectBehavior == HeadsetDisconnectBehavior.SWITCH_TO_SPEAKER) {
            // Deliberate user opt-in: keep going, audio now plays on the
            // speaker via the platform's own default routing.
            return
        }

        // Default behavior: immediately stop playback, do NOT fall back to
        // the speaker, keep the Gemini session alive underneath (no
        // reconnect needed) so reconnecting the headset resumes instantly.
        if (state == LiveSessionState.LISTENING || state == LiveSessionState.TRANSLATING) {
            stopCapturePlayback()
            transitionTo(LiveSessionState.PAUSED)
            updateNotification()
        }
    }

    // ---------------------------------------------------------------
    // Mic capture / playback
    // ---------------------------------------------------------------

    private fun beginCapturePlayback() {
        if (micActive.get()) return
        if (!requestAudioFocus()) {
            transitionTo(LiveSessionState.ERROR)
            return
        }

        // MODE_IN_COMMUNICATION, paired with runCaptureLoop's
        // AudioSource.VOICE_COMMUNICATION and this method's own
        // AudioAttributes.USAGE_VOICE_COMMUNICATION below: all three exist
        // together as one unit on every VoIP-style app for a reason — the
        // "voice communication" audio source/usage/attributes only get the
        // platform's special call-style routing and volume behavior applied
        // while AudioManager itself is told a communication session is
        // active. Left at the default MODE_NORMAL, some devices still
        // record fine (AudioSource.VOICE_COMMUNICATION alone can still
        // engage AEC) but route or attenuate USAGE_VOICE_COMMUNICATION
        // *playback* incorrectly — inaudible or near-silent output despite
        // AudioTrack.write() succeeding and the session otherwise working,
        // which is exactly what "shows Tłumaczę but nothing is heard" looks
        // like. Reset to MODE_NORMAL in stopCapturePlayback().
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // MODE_IN_COMMUNICATION alone does NOT guarantee speaker routing —
        // confirmed via a 2026 Android audio-routing writeup: mode-setting
        // only establishes communication context, and "without calling
        // setCommunicationDevice(), the system may default to the earpiece,
        // but this behavior isn't guaranteed across OEMs." This is the real,
        // evidenced explanation for a report that survived the
        // MODE_IN_COMMUNICATION fix above: on the reporting device, entering
        // communication mode silently routed BOTH playback and capture
        // through the earpiece path (tuned for a phone held to the ear),
        // which is why nothing was audible AND no speech was transcribed —
        // that earpiece-oriented gain staging barely picks up anything that
        // isn't a mouth pressed right against the top of the phone, let
        // alone speech from across a room or audio played from a PC. This
        // call only forces the loudspeaker when there is no private route to
        // use instead (see the SetupComplete branch above, the only caller
        // of this method: it's reached either because a headset IS
        // connected, or because the user opted into SWITCH_TO_SPEAKER) — a
        // connected headset should keep using the platform's own automatic
        // communication-device selection, not be overridden here.
        applyCommunicationRouting(forceSpeaker = !audioRouteMonitor.hasPrivateOutputRoute())

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
        transitionTo(LiveSessionState.LISTENING)

        val thread = Thread({ runCaptureLoop(minBufferSize) }, "TextGateLiveCapture")
        captureThread = thread
        thread.start()
    }

    /** See [beginCapturePlayback]'s call site for why this exists at all.
     * [android.media.AudioManager.setCommunicationDevice] is the modern
     * (API 31+) way to pick the device MODE_IN_COMMUNICATION actually
     * routes to; the legacy [android.media.AudioManager.isSpeakerphoneOn]
     * setter is kept only as the pre-31 fallback, since minSdk here is 26
     * (see AudioRouteMonitor's class doc). Always best-effort — never worth
     * failing the session over a routing call an OEM's HAL rejects. */
    private fun applyCommunicationRouting(forceSpeaker: Boolean) {
        if (forceSpeaker) {
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
        // forceSpeaker == false: a private route (headset) is connected —
        // leave routing to the platform's own automatic communication-device
        // selection rather than overriding it.
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

    @Suppress("MissingPermission") // RECORD_AUDIO is checked by the caller (Na żywo screen) before ACTION_START is ever sent.
    private fun runCaptureLoop(bufferSize: Int) {
        val record = try {
            AudioRecord(
                // VOICE_COMMUNICATION, not MIC: this session plays translated
                // audio out loud (speaker, per playbackTrack's own
                // USAGE_VOICE_COMMUNICATION above) WHILE simultaneously
                // capturing — exactly the "phone call" scenario Android's
                // audio source enum exists to distinguish. AudioSource.MIC
                // is a raw capture path with no echo handling, so on a
                // speaker (no headset) the mic keeps hearing the phone's own
                // translated output, sends it back to Gemini as new "speech",
                // and Gemini translates its own prior output again — a
                // self-sustaining loop that looks like "hears one sentence,
                // then repeats it forever" (a real report from this app's
                // own speaker testing). VOICE_COMMUNICATION routes capture
                // through the platform's telephony audio pipeline, which
                // applies acoustic echo cancellation (AEC) when the device
                // supports it. See [echoCanceler] below for the explicit,
                // defense-in-depth AEC attachment on top of this — some
                // devices' VOICE_COMMUNICATION source alone isn't enough.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: Exception) {
            mainHandler.post { handleDisconnect() }
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            mainHandler.post { handleDisconnect() }
            return
        }

        // Explicit AEC on top of the VOICE_COMMUNICATION source (see above)
        // — belt-and-braces, since not every device's VOICE_COMMUNICATION
        // path includes effective echo cancellation on its own, but
        // AcousticEchoCanceler.isAvailable() is itself unreliable on some
        // OEM builds, so this is best-effort and never fatal to the session.
        val echoCanceler = try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
            } else {
                null
            }
        } catch (_: Exception) {
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
            // Fall through to cleanup below — a capture-loop exception
            // ends this capture session; handleDisconnect (posted from the
            // outer catch when startRecording itself fails) is NOT called
            // here to avoid double-triggering reconnect logic from a
            // thread that is simply winding down after stopCapturePlayback().
        } finally {
            try {
                echoCanceler?.release()
            } catch (_: Exception) {
                // Best-effort cleanup only.
            }
            try {
                record.stop()
            } catch (_: Exception) {
                // Ignore — already stopping.
            }
            record.release()
        }
    }

    private fun stopCapturePlayback() {
        micActive.set(false)
        captureThread?.let {
            try {
                it.join(CAPTURE_THREAD_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                // Best-effort join only.
            }
        }
        captureThread = null
        playbackTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
                // Already stopped/released.
            }
        }
        playbackTrack = null
        // Symmetric with beginCapturePlayback's MODE_IN_COMMUNICATION and
        // applyCommunicationRouting — never leave the device's audio mode or
        // forced routing changed after this session's own capture/playback
        // has actually stopped, since both affect every app, not just this
        // one.
        resetCommunicationRouting()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ---------------------------------------------------------------
    // Audio focus
    // ---------------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { focusChange ->
                mainHandler.post { onAudioFocusChanged(focusChange) }
            }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onAudioFocusChanged(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app needs the mic/output more than we do right
                // now (e.g. an incoming phone call) — pause rather than
                // fight for it; the user can resume with START again once
                // that other app is done. This is NOT the headset-
                // disconnect pause state, but reuses the same safe
                // "stop capture/playback, keep the socket" shape.
                if (state == LiveSessionState.LISTENING || state == LiveSessionState.TRANSLATING) {
                    stopCapturePlayback()
                    transitionTo(LiveSessionState.PAUSED)
                    updateNotification()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (state == LiveSessionState.PAUSED && liveClient != null && audioRouteMonitor.hasPrivateOutputRoute()) {
                    beginCapturePlayback()
                }
            }
        }
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ---------------------------------------------------------------
    // WakeLock — PARTIAL only, never keeps the screen on (see class doc)
    // ---------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TextGateAI:LiveSession")
        lock.setReferenceCounted(false)
        // A generous safety-net timeout — normal paths always call
        // releaseWakeLock() explicitly; this only guards against a bug
        // leaving it held indefinitely.
        lock.acquire(WAKELOCK_SAFETY_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------
    // State + notification
    // ---------------------------------------------------------------

    private fun transitionTo(newState: LiveSessionState) {
        state = newState
        notifyListeners()
    }

    private fun notifyListeners() {
        stateListeners.toList().forEach { it(state) }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.live_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        // On Android 13+, posting a notification requires POST_NOTIFICATIONS
        // at runtime (requested the first time the user starts Na żywo — see
        // LiveTabController). If it was denied or not yet granted, skip the
        // call rather than let lint's MissingPermission check fire: the
        // service keeps running either way (startForeground() itself does
        // not require this permission), the user just won't see the
        // persistent status notification until they grant it.
        if (!MainActivity.hasNotificationPermission(this)) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentText = when (state) {
            LiveSessionState.STOPPED, LiveSessionState.CONNECTING -> getString(R.string.live_state_connecting)
            LiveSessionState.LISTENING, LiveSessionState.TRANSLATING -> getString(
                R.string.live_notification_active_format,
                (sourceLanguage?.englishName ?: getString(R.string.label_language_auto)),
                targetLanguage.englishName
            )
            LiveSessionState.RECONNECTING -> getString(R.string.live_state_reconnecting)
            LiveSessionState.PAUSED -> getString(R.string.live_notification_paused)
            LiveSessionState.ERROR -> getString(R.string.live_notification_error)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LiveTranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_notification)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.live_notification_stop_action), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.textgate.ai.live.action.START"
        const val ACTION_STOP = "com.textgate.ai.live.action.STOP"
        const val ACTION_RETRY = "com.textgate.ai.live.action.RETRY"
        const val EXTRA_SOURCE_LANGUAGE_CODE = "source_language_code"
        const val EXTRA_TARGET_LANGUAGE_CODE = "target_language_code"

        /** Independent of the two TEXT models — see GeminiLiveClient's and
         * TranslationOrchestrator's class docs for why these roles must
         * never mix. */
        const val LIVE_MODEL = "gemini-3.5-live-translate-preview"

        private const val NOTIFICATION_CHANNEL_ID = "textgate_live_translation"
        private const val NOTIFICATION_ID = 42

        private const val CAPTURE_SAMPLE_RATE_HZ = 16_000
        private const val PLAYBACK_SAMPLE_RATE_HZ = 24_000
        private const val MIN_BUFFER_FLOOR = 3_200 // 100ms of 16kHz mono 16-bit audio
        private const val CAPTURE_THREAD_JOIN_TIMEOUT_MS = 500L

        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val RECONNECT_BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)

        /** Safety-net only — every normal code path releases the WakeLock
         * explicitly well before this. */
        private const val WAKELOCK_SAFETY_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        fun startIntent(context: Context, sourceLanguageCode: String?, targetLanguageCode: String): Intent =
            Intent(context, LiveTranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE_LANGUAGE_CODE, sourceLanguageCode)
                .putExtra(EXTRA_TARGET_LANGUAGE_CODE, targetLanguageCode)

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveTranslationService::class.java).setAction(ACTION_STOP)

        fun start(context: Context, sourceLanguageCode: String?, targetLanguageCode: String) {
            val intent = startIntent(context, sourceLanguageCode, targetLanguageCode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
