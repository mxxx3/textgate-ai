package com.textgate.ai.network

import android.content.Context
import android.content.SharedPreferences
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Persisted per-model availability state for TEXT translation's automatic
 * fallback between the primary model ([com.textgate.ai.security.
 * AppSettingsStore.DEFAULT_MODEL], gemini-3.5-flash-lite) and
 * [TranslationOrchestrator.FALLBACK_MODEL] (gemini-3.1-flash-lite) — see
 * [TranslationOrchestrator], the only reader/writer of this store.
 *
 * Two independent kinds of "this model is temporarily unavailable" state
 * are tracked, because a 429 from Gemini does not always mean the same
 * thing (see [GeminiClient.Result.Failure.QuotaScope]):
 *   - DAILY (RPD): the model's daily request quota is exhausted. Persisted
 *     as a concrete reset instant, always computed as the next upcoming
 *     midnight in the `America/Los_Angeles` time zone — Google's quota
 *     reset zone for the Gemini API's free tier — deliberately NOT the
 *     device's own time zone and NOT a fixed UTC offset, so the
 *     computation stays correct across DST transitions automatically (see
 *     [nextLosAngelesMidnight]).
 *   - SHORT_TERM or UNKNOWN (RPM/TPM, or an unidentifiable 429): a short
 *     cooldown, using the server's own retry hint when available, or a
 *     conservative fixed default otherwise (see [markShortCooldown]).
 * Both survive an app restart — plain SharedPreferences, like every other
 * non-secret setting in this app (see [com.textgate.ai.security.
 * AppSettingsStore]); nothing stored here is a credential.
 */
class ModelAvailabilityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True while [model] should be skipped in favor of the fallback model —
     * i.e. an "unavailable until" timestamp is stored for it and is still
     * in the future. A stored timestamp that has already passed is treated
     * as expired (and lazily cleared right here) rather than acted on —
     * this is what makes "the very next real translation after a reset or
     * cooldown expires automatically retries the primary model" work, with
     * no separate background check or probe request: the next real
     * [TranslationOrchestrator.translateText] call just sees
     * [isUnavailable] return false on its own.
     */
    fun isUnavailable(model: String, now: Instant): Boolean {
        val until = unavailableUntil(model) ?: return false
        if (now.isBefore(until)) return true
        clear(model)
        return false
    }

    /** Marks [model] unavailable until the next America/Los_Angeles
     * midnight strictly after [now]. */
    fun markDailyExhausted(model: String, now: Instant) {
        val resetInstant = nextLosAngelesMidnight(now)
        prefs.edit()
            .putLong(untilKey(model), resetInstant.toEpochMilli())
            .putString(reasonKey(model), REASON_DAILY)
            .apply()
    }

    /** Marks [model] unavailable for a short cooldown — [retryAfterSeconds]
     * when the server supplied a positive value, otherwise
     * [DEFAULT_SHORT_COOLDOWN]. Never exceeds [MAX_SHORT_COOLDOWN]: a
     * garbled or unexpectedly large server hint must not be able to turn
     * what is really an RPM/TPM limit into an accidental multi-hour block. */
    fun markShortCooldown(model: String, now: Instant, retryAfterSeconds: Long?) {
        val requested = retryAfterSeconds?.takeIf { it > 0 }?.let { Duration.ofSeconds(it) }
            ?: DEFAULT_SHORT_COOLDOWN
        val cooldown = if (requested > MAX_SHORT_COOLDOWN) MAX_SHORT_COOLDOWN else requested
        prefs.edit()
            .putLong(untilKey(model), now.plus(cooldown).toEpochMilli())
            .putString(reasonKey(model), REASON_SHORT_TERM)
            .apply()
    }

    /** Clears any stored unavailability for [model] — called automatically
     * by [isUnavailable] once its stored timestamp has passed; also usable
     * directly (e.g. diagnostics) without waiting for that check. */
    fun clear(model: String) {
        prefs.edit().remove(untilKey(model)).remove(reasonKey(model)).apply()
    }

    private fun unavailableUntil(model: String): Instant? {
        val millis = prefs.getLong(untilKey(model), -1L)
        return if (millis <= 0L) null else Instant.ofEpochMilli(millis)
    }

    private fun untilKey(model: String) = "unavailable_until_${model.trim().lowercase()}"
    private fun reasonKey(model: String) = "unavailable_reason_${model.trim().lowercase()}"

    companion object {
        private const val PREFS_NAME = "textgate_model_availability"
        private const val REASON_DAILY = "daily"
        private const val REASON_SHORT_TERM = "short_term"
        private val DEFAULT_SHORT_COOLDOWN: Duration = Duration.ofSeconds(60)
        private val MAX_SHORT_COOLDOWN: Duration = Duration.ofMinutes(30)
        private val RESET_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

        /**
         * The next upcoming local midnight in [RESET_ZONE], strictly after
         * [now] — DST-aware (PST/PDT) automatically, since [ZonedDateTime]
         * resolves wall-clock arithmetic against the zone's real transition
         * rules rather than a fixed offset. `internal` (not `private`) so
         * this pure date-math can be unit-tested directly against
         * hand-picked instants, including ones that straddle a DST
         * transition, without depending on the current real date.
         */
        internal fun nextLosAngelesMidnight(now: Instant): Instant {
            val nowInZone = ZonedDateTime.ofInstant(now, RESET_ZONE)
            val nextMidnight = nowInZone.toLocalDate().plusDays(1).atStartOfDay(RESET_ZONE)
            return nextMidnight.toInstant()
        }
    }
}
