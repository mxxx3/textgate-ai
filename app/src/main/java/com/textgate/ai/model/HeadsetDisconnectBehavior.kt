package com.textgate.ai.model

/**
 * What an active "Na żywo" (ambient Live translation) session should do the
 * moment the user's headset (Bluetooth, wired, or USB audio) disconnects —
 * see [com.textgate.ai.security.AppSettingsStore.headsetDisconnectBehavior]
 * and the Foreground Service that owns the Live session
 * ([com.textgate.ai.live.LiveTranslationService]).
 *
 * [PAUSE_TRANSLATION] is the default, for a concrete privacy reason: an
 * accidental Bluetooth disconnect (out of range, low battery, another
 * device stealing the connection) must never cause the phone's own
 * loudspeaker to suddenly start playing a translation of what someone
 * nearby is saying — private audio the user specifically chose to route to
 * their own ears. On disconnect: playback stops immediately, output does
 * NOT fall back to the speaker, the Gemini Live session may stay open if
 * that is technically sensible (so resuming is instant, not a fresh
 * reconnect), and the screen shows "WSTRZYMANO — słuchawki odłączone" until
 * the user reconnects a headset (automatic or manual resume).
 *
 * [SWITCH_TO_SPEAKER] is an explicit, deliberate opt-in: the user has
 * decided they are fine with playback continuing on the phone's speaker if
 * their headset drops mid-session (e.g. they are alone, or don't mind).
 */
enum class HeadsetDisconnectBehavior(val prefValue: String) {
    PAUSE_TRANSLATION("pause"),
    SWITCH_TO_SPEAKER("switch_to_speaker");

    companion object {
        /** Falls back to [PAUSE_TRANSLATION] — the privacy-preserving
         * default — for a missing or unrecognized stored value, never
         * throws. Same defensive pattern as [UserGender.fromPrefValue]. */
        fun fromPrefValue(value: String?): HeadsetDisconnectBehavior =
            entries.firstOrNull { it.prefValue == value } ?: PAUSE_TRANSLATION
    }
}
