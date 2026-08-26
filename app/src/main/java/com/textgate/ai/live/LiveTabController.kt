package com.textgate.ai.live

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.widget.ArrayAdapter
import com.textgate.ai.LocaleHelper
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.databinding.ContentLiveBinding
import com.textgate.ai.model.AudioCaptureMode
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.security.AppSettingsStore

/**
 * Drives the "Na żywo" tab — binds to the already-independent
 * [LiveTranslationService] (started separately via [LiveTranslationService.
 * start], see [onStartStopClicked]) purely to REFLECT its state; this
 * controller never owns the mic, the Live session, or any audio resource
 * itself. This is what satisfies the spec's "reopening the app must detect
 * and reflect the existing session's state" requirement: binding in
 * [onStart] and reading [LiveTranslationService.state] (plus registering a
 * listener for further changes) works identically whether this Activity
 * was just created fresh or is being resumed while a session that started
 * minutes ago, screen off the whole time, is still running.
 *
 * The target language shown here is [LocaleHelper.resolvePreferredLanguage]
 * — the SAME setting the typed-trigger pipeline and the Tłumacz tab use —
 * shown read-only with a pointer to where to change it, per the spec's
 * explicit "reuse the existing language-preference logic, don't duplicate
 * the setting" instruction. Only the AMBIENT (source) language has a
 * picker on this screen, since that concept is new in v2.
 */
class LiveTabController(
    private val activity: Activity,
    private val binding: ContentLiveBinding
) {

    /** index 0 is "Automatycznie" (null = let Gemini detect the ambient
     * language); the rest mirror [Languages.ALL]. */
    private val ambientLanguageValues: List<SupportedLanguage?> = listOf(null) + Languages.ALL

    private val displayRouteMonitor = AudioRouteMonitor(activity.applicationContext)
    private val settingsStore = AppSettingsStore(activity.applicationContext)
    private var service: LiveTranslationService? = null
    private var bound = false
    private val stateListener: (LiveSessionState) -> Unit = { state -> activity.runOnUiThread { render(state) } }

    /** Held only while a session actually has audio flowing (mic listening
     * or translated audio playing) — see [render], which is the only
     * caller of [setAudioActive]. Two things a real phone call gets "for
     * free" from the platform that this screen has to ask for explicitly,
     * since it plays/records audio itself rather than going through the
     * telephony stack: the screen turning off when held to the ear (this
     * wake lock), and the hardware volume keys controlling ITS audio
     * specifically (see the [AudioManager] usage below). */
    private val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var proximityWakeLock: PowerManager.WakeLock? = null

    private fun setAudioActive(active: Boolean) {
        if (active) {
            // PROXIMITY_SCREEN_OFF_WAKE_LOCK — not deprecated (unlike
            // SCREEN_DIM_WAKE_LOCK/SCREEN_BRIGHT_WAKE_LOCK); it's the
            // standard, still-current way any calling/communication app
            // gets "screen off near the ear" without a telephony call of
            // its own. isWakeLockLevelSupported() guards devices where the
            // platform has no proximity sensor for this.
            if (proximityWakeLock == null &&
                powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
            ) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "TextGateAI:liveProximity"
                )
            }
            if (proximityWakeLock?.isHeld == false) proximityWakeLock?.acquire()
            // AudioTrack playback for this session uses
            // AudioAttributes.USAGE_VOICE_COMMUNICATION in ECHO_CANCELLED
            // mode (the default — see AudioCaptureMode's class doc), or
            // USAGE_MEDIA in STANDARD mode (see
            // LiveTranslationService.beginCapturePlayback), which the
            // platform maps to STREAM_VOICE_CALL or STREAM_MUSIC
            // respectively — but the hardware volume keys only follow that
            // mapping if this Activity explicitly says so; left at the
            // default they'd silently adjust whichever stream isn't
            // actually in use, so vol+/- would appear to do nothing.
            activity.volumeControlStream = if (settingsStore.audioCaptureMode == AudioCaptureMode.ECHO_CANCELLED) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
        } else {
            if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release()
            activity.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binderService: IBinder?) {
            val bindService = (binderService as? LiveTranslationService.LocalBinder)?.getService() ?: return
            service = bindService
            bindService.addStateListener(stateListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeStateListener(stateListener)
            service = null
        }
    }

    init {
        setupAmbientLanguageSpinner()
        updateTargetLanguageLabel()
        updateDeviceStatus()
        displayRouteMonitor.start { updateDeviceStatus() }

        binding.buttonLiveStartStop.setOnClickListener { onStartStopClicked() }
        binding.buttonLiveRetry.setOnClickListener {
            activity.startService(
                Intent(activity, LiveTranslationService::class.java).setAction(LiveTranslationService.ACTION_RETRY)
            )
        }
    }

    fun onStart() {
        val intent = Intent(activity, LiveTranslationService::class.java)
        activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
        updateTargetLanguageLabel()
    }

    fun onStop() {
        // Only detaches this Activity's UI — LiveTranslationService keeps
        // running independently (it was startForegroundService()'d, not
        // just bound) exactly as long as its own session lifecycle says it
        // should. See this class's own doc comment.
        if (bound) {
            service?.removeStateListener(stateListener)
            activity.unbindService(connection)
            bound = false
        }
        // The proximity/volume behavior in setAudioActive is specifically
        // "this screen is what's near the user's ear/hand right now" — that
        // stops being true the moment the Activity itself is backgrounded,
        // regardless of whether the underlying service session is still
        // running. Always safe to call even if nothing was held/set.
        setAudioActive(false)
    }

    fun onDestroy() {
        displayRouteMonitor.stop()
    }

    private fun setupAmbientLanguageSpinner() {
        val labels = listOf(activity.getString(R.string.label_language_auto)) + Languages.ALL.map { it.nativeName }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAmbientLanguage.adapter = adapter
        binding.spinnerAmbientLanguage.setSelection(0, false)
    }

    private fun updateTargetLanguageLabel() {
        val target = LocaleHelper.resolvePreferredLanguage(activity.applicationContext)
        binding.textLiveTargetLanguage.text =
            activity.getString(R.string.live_target_language_format, target.nativeName)
    }

    private fun updateDeviceStatus() {
        val hasPrivateRoute = displayRouteMonitor.hasPrivateOutputRoute()
        val routeLabel = when (displayRouteMonitor.currentOutputRoute()) {
            AudioRouteMonitor.OutputRoute.BLUETOOTH -> activity.getString(R.string.live_device_bluetooth)
            AudioRouteMonitor.OutputRoute.WIRED -> activity.getString(R.string.live_device_wired)
            AudioRouteMonitor.OutputRoute.USB -> activity.getString(R.string.live_device_usb)
            AudioRouteMonitor.OutputRoute.SPEAKER -> activity.getString(R.string.live_device_speaker)
        }
        binding.textLiveDeviceStatus.text = activity.getString(R.string.live_current_device_format, routeLabel)
        binding.textLiveHeadsetHint.text = if (hasPrivateRoute) {
            activity.getString(R.string.live_headset_connected_hint)
        } else {
            activity.getString(R.string.live_headset_missing_hint)
        }
    }

    private fun onStartStopClicked() {
        val current = service?.state ?: LiveSessionState.STOPPED
        if (current == LiveSessionState.STOPPED || current == LiveSessionState.ERROR) {
            requestPermissionsThenStart()
        } else {
            activity.startService(LiveTranslationService.stopIntent(activity))
        }
    }

    private fun requestPermissionsThenStart() {
        val missing = mutableListOf<String>()
        if (!MainActivity.hasRecordAudioPermission(activity)) missing += Manifest.permission.RECORD_AUDIO
        if (!MainActivity.hasNotificationPermission(activity) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), MainActivity.PERMISSION_REQUEST_RECORD_AUDIO_LIVE)
            return
        }
        startLiveService()
    }

    private fun startLiveService() {
        val ambientIndex = binding.spinnerAmbientLanguage.selectedItemPosition
        val ambientLanguage = ambientLanguageValues.getOrNull(ambientIndex)
        val targetLanguage = LocaleHelper.resolvePreferredLanguage(activity.applicationContext)
        LiveTranslationService.start(activity, ambientLanguage?.code, targetLanguage.code)
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != MainActivity.PERMISSION_REQUEST_RECORD_AUDIO_LIVE) return
        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (allGranted) startLiveService()
    }

    private fun render(state: LiveSessionState) {
        binding.textLiveState.text = activity.getString(state.labelRes)
        binding.buttonLiveStartStop.setText(
            if (state == LiveSessionState.STOPPED || state == LiveSessionState.ERROR) {
                R.string.live_button_start
            } else {
                R.string.live_button_stop
            }
        )
        binding.buttonLiveRetry.visibility =
            if (state == LiveSessionState.ERROR) android.view.View.VISIBLE else android.view.View.GONE

        val activeService = service
        if (activeService != null) {
            binding.textLiveInputTranscript.text = activeService.latestInputTranscript
            binding.textLiveOutputTranscript.text = activeService.latestOutputTranscript
        }

        // CONNECTING is included so the screen already behaves like a call
        // the moment the user presses start, not only once audio actually
        // starts flowing. PAUSED is deliberately excluded — that state
        // means the headset was disconnected and capture/playback are both
        // stopped (see LiveSessionState's own doc), so there's no reason to
        // keep the screen off or volume keys pointed at this session.
        val isAudioActive = state == LiveSessionState.CONNECTING ||
            state == LiveSessionState.LISTENING ||
            state == LiveSessionState.TRANSLATING ||
            state == LiveSessionState.RECONNECTING
        setAudioActive(isAudioActive)
    }
}
