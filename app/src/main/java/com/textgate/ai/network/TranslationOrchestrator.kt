package com.textgate.ai.network

import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import java.time.Instant

/**
 * Single entry point for every TEXT translation request TextGate AI makes
 * (the typed-trigger pipeline, the long-press bubble pipeline, and the
 * Settings "Test API" button, as of v2) — wraps [KeyRotationTranslator]
 * with an automatic, persisted primary/fallback MODEL choice, entirely
 * separate from and unaffected by key rotation:
 *   - primary: [AppSettingsStore.DEFAULT_MODEL] (gemini-3.5-flash-lite)
 *   - fallback: [FALLBACK_MODEL] (gemini-3.1-flash-lite)
 *
 * This orchestrator ONLY ever changes which MODEL is requested. It never
 * touches Gemini Live: Rozmowa and Na żywo call `GeminiLiveClient` directly
 * for `gemini-3.5-live-translate-preview`, never this object, and this
 * object's fallback logic must never be extended to cover that model — see
 * that client's own class doc for why the two model roles must stay
 * independent (a text-model quota problem must never switch Live audio to
 * a text model, and vice versa).
 *
 * Fallback substitution only ever applies when the caller's requested
 * model is EXACTLY the primary model. Any other model — including a
 * custom one the user has typed into Settings that happens to be neither
 * of the two models above — is sent exactly as requested, with no
 * substitution: this app has no way to know what, if anything, should
 * stand in for an arbitrary custom model, so it does not guess.
 *
 * On the primary model coming back [GeminiClient.Result.Failure.
 * AllKeysExhausted] (every stored key tried once, each 429), what happens
 * next depends on [GeminiClient.Result.Failure.QuotaScope], taken from the
 * last attempt's [GeminiClient.Result.Failure.QuotaExceeded] detail:
 *   - DAILY: the primary model is marked unavailable until the next
 *     America/Los_Angeles midnight (see [ModelAvailabilityStore]) — no
 *     further request that day will even try it first — and the CURRENT
 *     request is immediately retried once against the fallback model, so
 *     the user is not kept waiting on a request that has already failed.
 *   - SHORT_TERM: the primary model is marked unavailable for a short
 *     cooldown (using the server's own retry hint when present), and the
 *     current request is retried once against the fallback model the same
 *     way.
 *   - UNKNOWN (or no detail at all): treated exactly the same as
 *     SHORT_TERM — a short, safe cooldown — deliberately NEVER treated as
 *     DAILY. An ambiguous 429 must never be allowed to block the primary
 *     model for the rest of the day on a guess.
 *
 * No separate "probe" request is ever made to check whether the primary
 * model has recovered: the very next real translation after a stored
 * cooldown/reset expires simply tries the primary model again, because
 * [ModelAvailabilityStore.isUnavailable] treats an expired timestamp as
 * already cleared. If that retry succeeds, the primary model is back in
 * normal use with nothing further to do; if it still comes back exhausted,
 * this same logic runs again and recomputes a fresh cooldown/reset.
 */
object TranslationOrchestrator {

    /** Only known automatic-fallback target as of v2 — see this object's
     * class doc for exactly when it is and isn't substituted in. */
    const val FALLBACK_MODEL = "gemini-3.1-flash-lite"

    fun translateText(
        apiKeyStore: SecureApiKeyStore,
        availabilityStore: ModelAvailabilityStore,
        requestedModel: String,
        systemPrompt: String,
        userText: String,
        now: Instant = Instant.now()
    ): GeminiClient.Result {
        val primaryModel = AppSettingsStore.DEFAULT_MODEL

        if (!requestedModel.trim().equals(primaryModel, ignoreCase = true)) {
            // Not this app's known primary model (either the user's own
            // custom model, or already the fallback model itself) — no
            // fallback substitution applies, send exactly as requested.
            return KeyRotationTranslator.translateWithRotation(
                apiKeyStore = apiKeyStore,
                model = requestedModel,
                systemPrompt = systemPrompt,
                userText = userText
            )
        }

        val useModel = if (availabilityStore.isUnavailable(primaryModel, now)) FALLBACK_MODEL else primaryModel

        val result = KeyRotationTranslator.translateWithRotation(
            apiKeyStore = apiKeyStore,
            model = useModel,
            systemPrompt = systemPrompt,
            userText = userText
        )

        // Only a primary-model attempt that came back fully exhausted can
        // trigger the one-time same-request fallback retry below. A
        // fallback-model attempt that itself gets exhausted is returned to
        // the caller as-is — there is nothing left to fall back to.
        if (useModel != primaryModel) return result
        if (result !is GeminiClient.Result.Failure.AllKeysExhausted) return result

        when (result.lastQuotaDetail?.scope) {
            GeminiClient.Result.Failure.QuotaScope.DAILY ->
                availabilityStore.markDailyExhausted(primaryModel, now)
            GeminiClient.Result.Failure.QuotaScope.SHORT_TERM,
            GeminiClient.Result.Failure.QuotaScope.UNKNOWN,
            null ->
                availabilityStore.markShortCooldown(primaryModel, now, result.lastQuotaDetail?.retryAfterSeconds)
        }

        return KeyRotationTranslator.translateWithRotation(
            apiKeyStore = apiKeyStore,
            model = FALLBACK_MODEL,
            systemPrompt = systemPrompt,
            userText = userText
        )
    }
}
