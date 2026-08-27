package com.textgate.ai.live

import com.textgate.ai.R

/**
 * Maps a [GeminiLiveClient.LiveErrorCategory] to the user-facing string
 * resource both Live callers show — [LiveTranslationService] (Na żywo,
 * via [LiveTranslationService.lastErrorMessage]) and
 * [com.textgate.ai.conversation.ConversationTabController] (Rozmowa, via a
 * `Toast`). A single small file rather than duplicating this mapping in
 * both controllers — exactly the "reuse the same verified logic without
 * duplicating code" instruction this was added for (see both callers'
 * error-handling code for the differing RECONNECT-OR-NOT behavior around
 * this shared message choice: Na żywo reconnects with backoff for
 * NETWORK/UNKNOWN and fails immediately for the other three; Rozmowa never
 * reconnects at all, so this mapping is the only thing it actually needed
 * to share).
 */
internal fun liveErrorMessageRes(category: GeminiLiveClient.LiveErrorCategory): Int = when (category) {
    GeminiLiveClient.LiveErrorCategory.QUOTA -> R.string.live_error_quota
    GeminiLiveClient.LiveErrorCategory.AUTH -> R.string.live_error_auth
    GeminiLiveClient.LiveErrorCategory.CONFIG -> R.string.live_error_config
    GeminiLiveClient.LiveErrorCategory.NETWORK,
    GeminiLiveClient.LiveErrorCategory.UNKNOWN -> R.string.live_notification_error
}
