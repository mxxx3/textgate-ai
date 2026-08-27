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
import com.textgate.ai.LocaleHelper
import com.textgate.ai.MainActivity
import com.textgate.ai.R
import com.textgate.ai.databinding.ContentLiveBinding

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
 * the setting" instruction.
 *
 * The ambient (source) language is NOT a picker — as of the v2.x
 * routing/UI request, this screen no longer implies the user can choose
 * or influence what language Gemini hears: there is no request field that
 * lets this app force a source language (confirmed: no such field exists
 * anywhere in TranslationConfig/LiveConnectConfig — see
 * [GeminiLiveClient.ServerEvent.InputTranscript]'s doc). The ambient
 * language line always reads "Automatyczne wykrywanie" until
 * [LiveTranslationService.detectedSourceLanguage] reports what Gemini
 * itself detected, at which point it switches to "Wykryto: <language>" —
 * see [updateAmbientLanguageDisplay].
 */
class LiveTabController(
    private val activity: Activity,
    private val binding: ContentLiveBinding
) {

    private val displayRouteMonitor = AudioRouteMonitor(activity.applicationContext)
    private var service: LiveTranslationService? = null
    private var bound = false
    private val stateListener: (LiveSessionState) -> Unit = { state -> activity.runOnUiThread { render(state) } }

    /** TEMPORARY — see [LiveTranslationService]'s "TEMPORARY diagnostics"
     * section (above `addStateListener`). Separate from [stateListener] on
     * purpose: it fires every ~300ms while a session is active, and piggy-
     * backing that onto the real state-change listener would force a full
     * [render] several times a second for no reason. Delete this listener,
     * [updateDiagnosticsDisplay], and the registration/unregistration
     * calls below to remove the feature. Already runs on the UI thread —
     * [LiveTranslationService.notifyDiagnosticsListeners] is only ever
     * called from that service's own `mainHandler`, which is the main
     * Looper, same as every other cross-thread hand-off in this app. */
    private val diagnosticsListener: () -> Unit = { updateDiagnosticsDisplay() }

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
            // AudioTrack playback for this session always uses
            // AudioAttributes.USAGE_MEDIA now (see
            // LiveTranslationService.beginCapturePlayback's doc — output
            // routing is pinned explicitly via setPreferredDevice()
            // regardless of usage, so ECHO_CANCELLED no longer pairs with
            // USAGE_VOICE_COMMUNICATION/STREAM_VOICE_CALL), which the
            // platform maps to STREAM_MUSIC — but the hardware volume keys
            // only follow that mapping if this Activity explicitly says
            // so; left at the default they'd silently adjust whichever
            // stream isn't actually in use, so vol+/- would appear to do
            // nothing.
            activity.volumeControlStream = AudioManager.STREAM_MUSIC
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
            bindService.addDiagnosticsListener(diagnosticsListener) // TEMPORARY — see diagnosticsListener's doc.
            updateDiagnosticsDisplay()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeStateListener(stateListener)
            service?.removeDiagnosticsListener(diagnosticsListener) // TEMPORARY — see diagnosticsListener's doc.
            service = null
        }
    }

    init {
        updateAmbientLanguageDisplay()
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
            service?.removeDiagnosticsListener(diagnosticsListener) // TEMPORARY — see diagnosticsListener's doc.
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

    /** Static "Automatyczne wykrywanie" until Gemini itself reports a
     * detected language for this session — see this class's own doc for
     * why there is deliberately no picker here any more. Called from
     * [init] (before any session exists) and from every [render] (so it
     * picks up [LiveTranslationService.detectedSourceLanguage] as soon as
     * the first transcript with a language code arrives). */
    private fun updateAmbientLanguageDisplay() {
        val detected = service?.detectedSourceLanguage
        binding.textLiveAmbientLanguage.text = if (detected != null) {
            activity.getString(R.string.live_detected_language_format, detected.nativeName)
        } else {
            activity.getString(R.string.live_ambient_language_auto)
        }
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
        val targetLanguage = LocaleHelper.resolvePreferredLanguage(activity.applicationContext)
        LiveTranslationService.start(activity, targetLanguage.code)
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
        updateAmbientLanguageDisplay()

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

    /** TEMPORARY — see [diagnosticsListener]'s doc and
     * [LiveTranslationService]'s "TEMPORARY diagnostics" section. Formats
     * with `Locale.US` explicitly (not the device/app locale, e.g. Polish)
     * so the decimal separator is always "." per the requested
     * "Latency: X.X s" format — plain `String.format`/`"%.1f".format`
     * would silently use "," on a Polish-locale device instead. */
    private fun updateDiagnosticsDisplay() {
        val activeService = service ?: return
        binding.textLiveDiagLatencyCurrent.text = String.format(
            java.util.Locale.US, "Latency: %.1f s", activeService.diagLatencyCurrentSeconds
        )
        binding.textLiveDiagLatencyAverage.text = String.format(
            java.util.Locale.US, "Average: %.1f s", activeService.diagLatencyAverageSeconds
        )
        binding.textLiveDiagLatencyMax.text = String.format(
            java.util.Locale.US, "Max: %.1f s", activeService.diagLatencyMaxSeconds
        )
        binding.textLiveDiagAudioBacklog.text = String.format(
            java.util.Locale.US, "Audio backlog: %.1f s", activeService.diagAudioBacklogSeconds
        )
    }
}
