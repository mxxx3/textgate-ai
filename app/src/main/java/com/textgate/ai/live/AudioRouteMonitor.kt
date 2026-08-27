package com.textgate.ai.live

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Watches for headset (wired, Bluetooth, or USB audio) connect/disconnect
 * events via the platform `AudioDeviceCallback` API, and reports whether a
 * private (non-speaker) output route is currently available. This is the
 * ONLY thing [LiveTranslationService] consults to decide whether Live
 * translation audio can play somewhere other than the phone's own
 * loudspeaker — see [com.textgate.ai.security.AppSettingsStore.
 * headsetDisconnectBehavior] for what happens when this flips from true to
 * false during an active session.
 *
 * Deliberately platform-only (`AudioDeviceCallback`, API 23+; this app's
 * minSdk is 26) — no third-party Bluetooth/audio-routing library, per this
 * app's zero-production-dependency policy for anything other than the
 * Gemini Live WebSocket connection itself (see build.gradle.kts and
 * GeminiLiveClient's own class doc).
 */
class AudioRouteMonitor(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var listener: ((Boolean) -> Unit)? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = notifyState()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = notifyState()
    }

    /** True while at least one connected OUTPUT device is something other
     * than the phone's own built-in speaker or earpiece — i.e. a wired
     * headset, Bluetooth headset/earbuds, or USB audio device is currently
     * available to route audio to privately. */
    fun hasPrivateOutputRoute(): Boolean = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUT_TYPES }
    } catch (_: Exception) {
        false
    }

    /** The specific output [AudioDeviceInfo] a session should pin playback
     * to via `AudioTrack.setPreferredDevice()` (API 24+; per-instance, not
     * global — see [LiveTranslationService.beginCapturePlayback] and
     * [com.textgate.ai.conversation.ConversationTabController.
     * beginCapturePlayback] for the callers) — the first available private
     * route (wired/Bluetooth/USB), or, when none is connected, the phone's
     * OWN built-in speaker, explicit rather than left to the platform's
     * default so routing stays as deterministic as possible across
     * different OEMs (this app's minSdk is 26, well above the API-24
     * requirement, so no version check is needed). Null only if the device
     * genuinely reports no output devices at all — not expected in
     * practice, but `getDevices()` documents no non-empty guarantee. */
    fun selectPreferredOutputDevice(): AudioDeviceInfo? {
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }
        return devices.firstOrNull { it.type in PRIVATE_OUTPUT_TYPES }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /** The phone's own built-in microphone, for `AudioRecord.
     * setPreferredDevice()` — always pinned here by the callers above
     * REGARDLESS of what output is connected, so a Bluetooth/TWS headset's
     * own mic is never silently used for ambient capture instead: the
     * whole point of Na żywo/Rozmowa capturing from the phone itself is
     * that it hears the room, not whatever a tiny earbud mic happens to
     * pick up pressed into an ear canal. Null only if the device genuinely
     * has no built-in mic reported (not expected on a phone, but
     * defensive all the same — the callers fall back to the platform's
     * default input in that case). */
    fun selectBuiltInMicDevice(): AudioDeviceInfo? {
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (_: Exception) {
            emptyArray()
        }
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    /** A short, human-readable category for the CURRENT best output route —
     * used by the Na żywo screen's "current audio device" line. Never more
     * specific than the device category (no device names), consistent
     * with this app's general "no unnecessary detail" philosophy. */
    fun currentOutputRoute(): OutputRoute {
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }
        return when {
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ->
                OutputRoute.BLUETOOTH
            devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } ->
                OutputRoute.WIRED
            devices.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE } ->
                OutputRoute.USB
            else -> OutputRoute.SPEAKER
        }
    }

    /** Begins reporting route changes to [onRouteChanged] (called with the
     * new [hasPrivateOutputRoute] value) whenever a device is added or
     * removed. Must be paired with [stop] — typically from
     * [LiveTranslationService]'s START/STOP lifecycle, never left
     * registered past an active session. */
    fun start(onRouteChanged: (hasPrivateRoute: Boolean) -> Unit) {
        listener = onRouteChanged
        try {
            audioManager.registerAudioDeviceCallback(callback, null)
        } catch (_: Exception) {
            // Defensive only — registerAudioDeviceCallback does not
            // document throwing, but nothing here is worth crashing over.
        }
    }

    fun stop() {
        try {
            audioManager.unregisterAudioDeviceCallback(callback)
        } catch (_: Exception) {
            // Safe to call even if never registered.
        }
        listener = null
    }

    private fun notifyState() {
        listener?.invoke(hasPrivateOutputRoute())
    }

    enum class OutputRoute { SPEAKER, WIRED, BLUETOOTH, USB }

    companion object {
        /** `internal`, not `private` — [LiveTranslationService] and
         * [com.textgate.ai.conversation.ConversationTabController] both
         * need this exact same device-type set to decide whether the
         * output device [selectPreferredOutputDevice] resolved is a
         * private route (skip the heavier AEC pipeline) or the phone's own
         * speaker (use it) — reusing this one definition rather than each
         * keeping its own copy. */
        internal val PRIVATE_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
    }
}
