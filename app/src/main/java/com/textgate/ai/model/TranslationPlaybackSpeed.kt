package com.textgate.ai.model

/**
 * How fast Na żywo plays back the translated audio it RECEIVES from Gemini
 * — see [com.textgate.ai.security.AppSettingsStore.translationPlaybackSpeed]
 * and [com.textgate.ai.live.LiveTranslationService.applyPlaybackSpeed] for
 * where this is actually read and applied.
 *
 * Added after real on-device feedback: the app owner found Gemini's
 * translated voice noticeably slow, and specifically asked for faster
 * playback WITHOUT the pitch going up (no "chipmunk" effect) and WITHOUT
 * adding the latency [com.textgate.ai.model.AudioCaptureMode.ECHO_CANCELLED]'s
 * heavier capture pipeline has.
 *
 * This setting only ever affects [com.textgate.ai.live.LiveTranslationService
 * .playbackTrack] — the audio already received from Gemini and about to be
 * played back — via `AudioTrack.PlaybackParams` (confirmed against
 * Android's own reference docs, mirrored at learn.microsoft.com/dotnet/api/
 * android.media.playbackparams: "Pitch equals 1.0f. Speed change will be
 * done with pitch preserved, often called timestretching" — exactly the
 * mechanism this needed). It never touches: the audio sent TO Gemini (mic
 * capture — [com.textgate.ai.live.LiveTranslationService.runCaptureLoop]/
 * [com.textgate.ai.live.LiveTranslationService.micSource] are unrelated),
 * the sample rate declared to Gemini or configured on either
 * [android.media.AudioTrack]/[android.media.AudioRecord] (this changes
 * playback SPEED, never the format/rate a track is built or written at),
 * transcription, translation content, VAD, or audio routing (EARPIECE/
 * Bluetooth/speaker selection is completely independent of this).
 *
 * [FASTER] (1.25x) is the default: the app owner's own reported "noticeably
 * slow" baseline was 1.0x, and 1.25x was specifically requested as the new
 * default starting point, adjustable in Settings without needing another
 * round trip.
 */
enum class TranslationPlaybackSpeed(val prefValue: String, val multiplier: Float) {
    NORMAL("1.0", 1.0f),
    SLIGHTLY_FASTER("1.15", 1.15f),
    FASTER("1.25", 1.25f),
    QUITE_FAST("1.35", 1.35f),
    FASTEST("1.5", 1.5f);

    companion object {
        /** Falls back to [FASTER] — the requested new default — for a
         * missing or unrecognized stored value, never throws. Same
         * defensive pattern as [AudioCaptureMode.fromPrefValue]. */
        fun fromPrefValue(value: String?): TranslationPlaybackSpeed =
            entries.firstOrNull { it.prefValue == value } ?: FASTER
    }
}
