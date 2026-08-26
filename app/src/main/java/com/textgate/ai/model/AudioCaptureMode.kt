package com.textgate.ai.model

/**
 * How Rozmowa and Na żywo capture and play audio during a Live session —
 * see [com.textgate.ai.security.AppSettingsStore.audioCaptureMode] and both
 * controllers' `applyCommunicationRouting()`/`runCaptureLoop()` for where
 * this is actually read and acted on.
 *
 * This exists because of two real, evidenced, and genuinely conflicting
 * on-device reports from the same app owner testing the same feature:
 *
 * 1. With plain [MediaRecorder.AudioSource.MIC] and no call-style audio
 *    mode/routing (this app's original v2.0.0 behavior), playing translated
 *    audio on the phone's own loudspeaker (no headset) let the microphone
 *    hear that same output and send it back to Gemini as new "speech" —
 *    a self-sustaining echo loop ("hears one sentence, then repeats it
 *    forever").
 * 2. The fix for that — [MediaRecorder.AudioSource.VOICE_COMMUNICATION],
 *    an explicit [android.media.audiofx.AcousticEchoCanceler],
 *    `AudioManager.mode = MODE_IN_COMMUNICATION`, and forcing the speaker
 *    via `setCommunicationDevice()`/`setSpeakerphoneOn()` — solved the echo
 *    loop, but on the SAME reporting device introduced its own real
 *    regression: noticeable lag and turns being cut off and replaced by a
 *    new one, evidently because engaging the platform's full telephony-style
 *    audio pipeline (echo cancellation, noise suppression, AGC, and the
 *    device-switch itself) adds processing latency and occasional
 *    discontinuities that Gemini's own turn-detection reads as the end of
 *    one utterance and the start of another.
 *
 * Neither behavior is strictly better — which one actually works well
 * varies by device/OEM audio HAL, which this project has no way to detect
 * or test for automatically (no device fleet, no real compiler/emulator in
 * this sandbox — see this project's standing sandbox-limitation note).
 * Rather than silently pick one and leave the other report unaddressed,
 * this is a user-facing choice (Settings > Audio i Live > "Tryb
 * przechwytywania dźwięku"), exactly as the app owner asked for: "do
 * wyboru" (as a choice).
 */
enum class AudioCaptureMode(val prefValue: String) {
    /** [MediaRecorder.AudioSource.VOICE_COMMUNICATION] + explicit
     * [android.media.audiofx.AcousticEchoCanceler] + `MODE_IN_COMMUNICATION`
     * + forced communication-device routing. Avoids the speaker echo loop;
     * may add latency or cause cut-off turns on some phones. Default,
     * since an echo loop (repeating itself forever) is a worse default
     * experience than extra latency for most users, most of the time. */
    ECHO_CANCELLED("echo_cancelled"),

    /** Plain [MediaRecorder.AudioSource.MIC], no `AudioManager.mode` change,
     * no forced device routing, no [android.media.audiofx.
     * AcousticEchoCanceler] — this app's original v2.0.0 capture/playback
     * path. Faster and simpler, at the real cost of the speaker echo loop
     * described above; intended for use with headphones, where there is no
     * loudspeaker for the mic to hear in the first place. */
    STANDARD("standard");

    companion object {
        /** Falls back to [ECHO_CANCELLED] for a missing or unrecognized
         * stored value, never throws — same defensive pattern as
         * [HeadsetDisconnectBehavior.fromPrefValue]. */
        fun fromPrefValue(value: String?): AudioCaptureMode =
            entries.firstOrNull { it.prefValue == value } ?: ECHO_CANCELLED
    }
}
