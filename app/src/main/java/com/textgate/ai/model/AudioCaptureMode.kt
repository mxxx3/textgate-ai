package com.textgate.ai.model

/**
 * How Rozmowa and Na żywo decide whether to engage the heavier echo-
 * cancelled capture pipeline during a Live session — see
 * [com.textgate.ai.security.AppSettingsStore.audioCaptureMode] and both
 * controllers' `beginCapturePlayback()`/`runCaptureLoop()` for where this
 * is actually read and acted on.
 *
 * This exists because of two real, evidenced, and genuinely conflicting
 * on-device reports from the same app owner testing the same feature:
 *
 * 1. With plain [MediaRecorder.AudioSource.MIC] and no echo handling (this
 *    app's original v2.0.0 behavior), playing translated audio on the
 *    phone's own loudspeaker (no headset) let the microphone hear that
 *    same output and send it back to Gemini as new "speech" — a
 *    self-sustaining echo loop ("hears one sentence, then repeats it
 *    forever").
 * 2. The first fix for that — unconditional
 *    [MediaRecorder.AudioSource.VOICE_COMMUNICATION], an explicit
 *    [android.media.audiofx.AcousticEchoCanceler], and
 *    `AudioManager.mode = MODE_IN_COMMUNICATION` with forced
 *    communication-device routing, applied EVERY session regardless of
 *    what was connected — solved the echo loop, but on the SAME reporting
 *    device introduced its own real regression even with headphones on:
 *    noticeable lag and turns being cut off and replaced by a new one,
 *    evidently because engaging the platform's full telephony-style audio
 *    pipeline (echo cancellation, noise suppression, AGC, and the global
 *    device-mode switch itself) adds processing latency and occasional
 *    discontinuities that Gemini's own turn-detection reads as the end of
 *    one utterance and the start of another — latency headphones never
 *    needed to pay in the first place, since the mic essentially can't
 *    hear headphone output at all.
 *
 * As of the v2.x routing rework, the actual AEC-or-not decision is made
 * AUTOMATICALLY and per-session from the real resolved output route (see
 * [AudioRouteMonitor.selectPreferredOutputDevice] and both controllers'
 * `beginCapturePlayback()`): the heavier pipeline only ever engages when
 * the resolved output is the phone's own speaker (no private route
 * connected) — headphones/Bluetooth/USB always get the light,
 * lower-latency path, with no manual choice required for the common case.
 * [ECHO_CANCELLED] (default) is that automatic behavior; [STANDARD] is
 * kept as an explicit escape hatch that forces the light path even on the
 * speaker (accepting the echo-loop risk) for a device/HAL combination
 * where AEC itself misbehaves — which this project has no fleet to detect
 * automatically (no device fleet, no real compiler/emulator in this
 * sandbox — see this project's standing sandbox-limitation note), so it
 * stays a user-facing override (Settings > Audio i Live > "Tryb
 * przechwytywania dźwięku") rather than being removed.
 */
enum class AudioCaptureMode(val prefValue: String) {
    /** Automatic (recommended): [MediaRecorder.AudioSource.
     * VOICE_COMMUNICATION] + explicit [android.media.audiofx.
     * AcousticEchoCanceler] ONLY when the resolved output route is the
     * phone's own speaker; headphones/Bluetooth/USB always get the plain,
     * light [MediaRecorder.AudioSource.MIC] path instead. Default, since
     * an echo loop on the speaker (repeating itself forever) is a worse
     * default experience than the AEC pipeline's extra latency in that one
     * case, while headphone use — the common case — pays no latency cost
     * at all under this mode. */
    ECHO_CANCELLED("echo_cancelled"),

    /** Always [MediaRecorder.AudioSource.MIC], never
     * [android.media.audiofx.AcousticEchoCanceler], regardless of the
     * resolved output route — this app's original v2.0.0 capture path,
     * forced unconditionally. Fastest and simplest, at the real cost of
     * the speaker echo loop described above if used without headphones;
     * an explicit override for a device where AEC itself is the problem,
     * not the recommended everyday choice now that [ECHO_CANCELLED]
     * already skips the heavy path automatically whenever headphones are
     * connected. */
    STANDARD("standard");

    companion object {
        /** Falls back to [ECHO_CANCELLED] for a missing or unrecognized
         * stored value, never throws — same defensive pattern as
         * [HeadsetDisconnectBehavior.fromPrefValue]. */
        fun fromPrefValue(value: String?): AudioCaptureMode =
            entries.firstOrNull { it.prefValue == value } ?: ECHO_CANCELLED
    }
}
