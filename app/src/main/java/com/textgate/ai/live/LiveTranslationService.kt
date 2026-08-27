package com.textgate.ai.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
import com.textgate.ai.model.AudioCaptureMode
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

    /** The ambient language Gemini itself reported detecting, via
     * `serverContent.inputTranscription.languageCode` — see
     * [GeminiLiveClient.ServerEvent.InputTranscript]'s doc for why this,
     * not a user-facing picker, is the only real signal: there is no
     * request field that lets this app FORCE a source language (confirmed:
     * no such field exists in TranslationConfig/LiveConnectConfig). Null
     * until the first transcript with a language code arrives, or if the
     * server never includes one. Shown in the UI as "Wykryto: X" — see
     * [LiveTabController]. */
    var detectedSourceLanguage: SupportedLanguage? = null
        private set
    var targetLanguage: SupportedLanguage = Languages.DEFAULT
        private set
    var latestInputTranscript: String = ""
        private set
    var latestOutputTranscript: String = ""
        private set

    /** A category-specific, user-readable message set whenever [state]
     * becomes [LiveSessionState.ERROR] — see [handleLiveError]/
     * [failSession]/[handleDisconnect]'s exhausted-attempts branch, the
     * only three writers. Read by [LiveTabController] and by
     * [buildNotification] so the ERROR state always shows something more
     * specific than a generic "coś poszło nie tak" when the cause is
     * known (quota, API key, or config problem — see point 9 of the v2.x
     * request this was added for). Null only before any error has
     * occurred this process lifetime. */
    var lastErrorMessage: String? = null
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
        lastErrorMessage = null
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
                event.languageCode?.let { code -> Languages.byCode(code)?.let { detectedSourceLanguage = it } }
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
            is ServerEvent.Error -> handleLiveError(event.category)
            is ServerEvent.Closed -> if (event.code != 1000) handleLiveError(event.category)
        }
        updateNotification()
    }

    /** Routes a Live error/close event to either a bounded, backed-off
     * reconnect (genuine network trouble — the existing, proven behavior,
     * unchanged) or an immediate, non-retrying failure with a specific
     * message (quota/rate-limit, bad API key, or invalid config/unsupported
     * language — none of these are fixed by reconnecting, so retrying them
     * in a loop would just spam the same rejection). See
     * [GeminiLiveClient.classifyLiveError]'s doc for how [category] is
     * determined, and [liveErrorMessageRes] (shared with
     * [com.textgate.ai.conversation.ConversationTabController], per this
     * same request's point about reusing this without duplicating it) for
     * the message mapping. */
    private fun handleLiveError(category: GeminiLiveClient.LiveErrorCategory) {
        when (category) {
            GeminiLiveClient.LiveErrorCategory.QUOTA,
            GeminiLiveClient.LiveErrorCategory.AUTH,
            GeminiLiveClient.LiveErrorCategory.CONFIG -> failSession(category)
            GeminiLiveClient.LiveErrorCategory.NETWORK,
            GeminiLiveClient.LiveErrorCategory.UNKNOWN -> handleDisconnect()
        }
    }

    /** Terminal, non-retrying failure — the counterpart to
     * [handleDisconnect]'s reconnect path, for error categories a
     * reconnect can never fix (see [handleLiveError]). Releases exactly
     * the same resources [handleDisconnect]'s exhausted-attempts branch
     * does, but immediately, without spending any of the 5 reconnect
     * attempts on a rejection that would just repeat identically. */
    private fun failSession(category: GeminiLiveClient.LiveErrorCategory) {
        stopCapturePlayback()
        liveClient?.close()
        liveClient = null
        releaseWakeLock()
        releaseAudioFocus()
        audioRouteMonitor.stop()
        lastErrorMessage = getString(liveErrorMessageRes(category))
        transitionTo(LiveSessionState.ERROR)
    }

    private fun handleDisconnect() {
        stopCapturePlayback()
        liveClient?.close()
        liveClient = null

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            releaseWakeLock()
            releaseAudioFocus()
            audioRouteMonitor.stop()
            lastErrorMessage = getString(R.string.live_notification_error)
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
        detectedSourceLanguage = null
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

        // v2.x routing rework (points 3/4/5 of the request that replaced
        // the MODE_IN_COMMUNICATION/setCommunicationDevice approach below):
        // rather than relying on Android's own call-style routing — which,
        // on at least one reporting device, added noticeable lag and cut
        // off turns even with headphones connected — pin OUTPUT explicitly
        // via AudioTrack.setPreferredDevice() to the best resolved device
        // (private route if connected, else the phone's own speaker,
        // deterministic either way), and pin INPUT explicitly via
        // AudioRecord.setPreferredDevice() (in runCaptureLoop) to the
        // phone's own built-in mic — regardless of what output is
        // connected, so a Bluetooth/TWS headset's mic is never silently
        // used for ambient capture. Both are per-instance API-24+ calls
        // (this app's minSdk is 26), so nothing here touches
        // AudioManager.mode or any other app's routing.
        val outputDevice = audioRouteMonitor.selectPreferredOutputDevice()
        val hasPrivateOutput = outputDevice != null && outputDevice.type in AudioRouteMonitor.PRIVATE_OUTPUT_TYPES

        // AEC/VOICE_COMMUNICATION is now decided AUTOMATICALLY from the
        // real resolved output route rather than applied unconditionally —
        // see AudioCaptureMode's class doc for the full story.
        // ECHO_CANCELLED (default): use the heavier echo-cancelled pipeline
        // ONLY when there is no private route (about to play on the
        // phone's own speaker, where the mic really can hear it);
        // headphones/Bluetooth/USB skip it entirely, since the mic
        // essentially can't hear headphone output and the heavier pipeline
        // was the evidenced source of added latency and cut-off turns.
        // STANDARD: always use the light path, even on speaker, as an
        // explicit user override.
        val captureMode = settingsStore.audioCaptureMode
        val useAec = captureMode == AudioCaptureMode.ECHO_CANCELLED && !hasPrivateOutput

        val minBufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(MIN_BUFFER_FLOOR)

        // USAGE_MEDIA unconditionally now (previously switched to
        // USAGE_VOICE_COMMUNICATION in ECHO_CANCELLED mode, mapped to the
        // legacy STREAM_VOICE_CALL) — output routing is handled explicitly
        // by setPreferredDevice() above regardless of usage, so there is no
        // remaining reason to pair this with the telephony-style usage;
        // volumeControlStream (see LiveTabController.setAudioActive)
        // follows this same simplification to STREAM_MUSIC.
        playbackTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        if (outputDevice != null) {
            try {
                playbackTrack?.setPreferredDevice(outputDevice)
            } catch (_: Exception) {
                // Best-effort only — playback still works via the
                // platform's own default routing if an OEM rejects the pin.
            }
        }
        playbackTrack?.play()

        micActive.set(true)
        transitionTo(LiveSessionState.LISTENING)

        val thread = Thread({ runCaptureLoop(minBufferSize, useAec) }, "TextGateLiveCapture")
        captureThread = thread
        thread.start()
    }

    @Suppress("MissingPermission") // RECORD_AUDIO is checked by the caller (Na żywo screen) before ACTION_START is ever sent.
    private fun runCaptureLoop(bufferSize: Int, useAec: Boolean) {
        val record = try {
            AudioRecord(
                // VOICE_COMMUNICATION only when useAec (resolved output is
                // the speaker, ECHO_CANCELLED mode) — see
                // beginCapturePlayback's doc for the full, now automatic,
                // per-session reasoning. Plain AudioSource.MIC otherwise
                // (headphones connected, or STANDARD mode's explicit
                // override): a raw capture path with no echo handling,
                // correct exactly when the mic can't hear the output in the
                // first place.
                if (useAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
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

        // Always pin capture to the phone's own built-in mic — see
        // beginCapturePlayback's doc (point 4 of the routing request: never
        // silently let a connected Bluetooth/TWS headset's mic take over
        // ambient capture). Best-effort: some OEMs may reject this for a
        // given AudioSource, in which case capture simply continues on
        // whatever device the platform picked by default.
        audioRouteMonitor.selectBuiltInMicDevice()?.let { mic ->
            try {
                record.setPreferredDevice(mic)
            } catch (_: Exception) {
                // Best-effort only.
            }
        }

        // Explicit AEC on top of the VOICE_COMMUNICATION source (see above)
        // — belt-and-braces, since not every device's VOICE_COMMUNICATION
        // path includes effective echo cancellation on its own, but
        // AcousticEchoCanceler.isAvailable() is itself unreliable on some
        // OEM builds, so this is best-effort and never fatal to the
        // session. Only attempted when useAec.
        val echoCanceler = if (useAec) {
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
        // No AudioManager.mode/communication-device state to reset any
        // more — see beginCapturePlayback's doc: routing is now pinned
        // per-instance via setPreferredDevice() (AudioTrack/AudioRecord),
        // never through the global AudioManager.mode, so there is nothing
        // session-wide left over that could affect another app.
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
                (detectedSourceLanguage?.englishName ?: getString(R.string.label_language_auto)),
                targetLanguage.englishName
            )
            LiveSessionState.RECONNECTING -> getString(R.string.live_state_reconnecting)
            LiveSessionState.PAUSED -> getString(R.string.live_notification_paused)
            LiveSessionState.ERROR -> lastErrorMessage ?: getString(R.string.live_notification_error)
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

        fun startIntent(context: Context, targetLanguageCode: String): Intent =
            Intent(context, LiveTranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TARGET_LANGUAGE_CODE, targetLanguageCode)

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveTranslationService::class.java).setAction(ACTION_STOP)

        fun start(context: Context, targetLanguageCode: String) {
            val intent = startIntent(context, targetLanguageCode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
