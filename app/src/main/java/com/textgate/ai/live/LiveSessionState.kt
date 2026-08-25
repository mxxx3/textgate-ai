package com.textgate.ai.live

import com.textgate.ai.R

/**
 * The 7 required states of an ambient "Na żywo" Live session — spec exact
 * wording (Polish, shown to the user via [labelRes]): ZATRZYMANO,
 * ŁĄCZENIE, SŁUCHAM, TŁUMACZĘ, PONOWNE ŁĄCZENIE, WSTRZYMANO, BŁĄD. Owned
 * and transitioned exclusively by [LiveTranslationService] — the Na żywo
 * screen only ever reads the current state via
 * [LiveTranslationService.LocalBinder], never sets it directly.
 */
enum class LiveSessionState {
    /** No session active. The only state the service can be started INTO —
     * see [LiveTranslationService]'s class doc: START is always a
     * deliberate, explicit user action, never automatic. */
    STOPPED,

    /** Opening the Gemini Live WebSocket session; mic not yet capturing. */
    CONNECTING,

    /** Session open, actively listening to ambient audio, no active
     * translation turn in progress right now. */
    LISTENING,

    /** A translation turn is in progress — translated audio is arriving
     * and/or playing. */
    TRANSLATING,

    /** The connection dropped and a bounded, backed-off reconnect attempt
     * is in progress (see [LiveTranslationService.MAX_RECONNECT_ATTEMPTS]). */
    RECONNECTING,

    /** Headset disconnected while [com.textgate.ai.model.
     * HeadsetDisconnectBehavior.PAUSE_TRANSLATION] is the active setting —
     * mic capture and playback are both paused; the session itself may
     * still be alive underneath (see [LiveTranslationService]'s headset-
     * disconnect handling) so reconnecting a headset can resume instantly. */
    PAUSED,

    /** Reconnect attempts were exhausted, or an unrecoverable error
     * occurred. All resources (mic, socket, audio focus, WakeLock) are
     * already released by the time this state is reported — only a fresh
     * START (offered as "Spróbuj ponownie" in the UI) can recover. */
    ERROR;

    val labelRes: Int
        get() = when (this) {
            STOPPED -> R.string.live_state_stopped
            CONNECTING -> R.string.live_state_connecting
            LISTENING -> R.string.live_state_listening
            TRANSLATING -> R.string.live_state_translating
            RECONNECTING -> R.string.live_state_reconnecting
            PAUSED -> R.string.live_state_paused
            ERROR -> R.string.live_state_error
        }
}
