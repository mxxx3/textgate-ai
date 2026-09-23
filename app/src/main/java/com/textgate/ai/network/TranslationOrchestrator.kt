package com.textgate.ai.network

import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Text translation model failover. Live voice translation deliberately stays
 * separate: a text-only Gemini model cannot replace Gemini Live.
 *
 * Honor the user's selected model first. On transient failures only, walk a
 * private, documented set of text-capable fallback models. These alternatives
 * are NOT added to the settings suggestions or selected-model preference.
 *
 * Persistent per-model cooldown prevents every chat message from repeatedly
 * waiting on a model that is currently returning 503. There are no proactive
 * health checks: only actual, user-requested translations consume quota.
 */
object TranslationOrchestrator {
    const val FALLBACK_MODEL = "gemini-3.1-flash-lite"

    // Ordered for the project's observed FREE tier (September 2026):
    // the first two have 500 RPD, remaining models have 20 RPD. Keep the
    // known-working 2.5 Flash ahead of unverified lower-quota alternatives.
    // All identifiers are stable text models listed in Google's API docs.
    internal val HIDDEN_FALLBACK_MODELS = listOf(
        AppSettingsStore.DEFAULT_MODEL,
        FALLBACK_MODEL,
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash"
    )

    // A single chat translation must not work through every model after an
    // outage. Each model gets a short socket/read timeout, and the whole
    // request has a cooperative elapsed-time budget checked between models.
    // HttpsURLConnection's connect/read timeouts are per I/O stage rather
    // than a hard wall-clock deadline, so this is a latency target, NOT
    // a promise of an exact 12-second maximum on every network/device.
    internal const val MAX_MODEL_ATTEMPTS = 4
    internal const val TOTAL_BUDGET_MS = 12_000L
    internal const val PER_MODEL_TIMEOUT_MS = 3_000
    private const val MIN_REMAINING_MS = 1_000L

    fun translateText(
        apiKeyStore: SecureApiKeyStore,
        availabilityStore: ModelAvailabilityStore,
        requestedModel: String,
        systemPrompt: String,
        userText: String,
        now: Instant = Instant.now()
    ): GeminiClient.Result {
        val startNanos = System.nanoTime()
        return translateWithFallback(
            availabilityStore = availabilityStore,
            requestedModel = requestedModel,
            now = now,
            elapsedMillis = {
                TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startNanos).coerceAtLeast(0L))
            },
            attempt = { model, timeoutMs ->
                KeyRotationTranslator.translateWithRotation(
                    apiKeyStore = apiKeyStore,
                    model = model,
                    systemPrompt = systemPrompt,
                    userText = userText,
                    timeoutMs = timeoutMs
                )
            }
        )
    }

    /** Internal seam permits complete, deterministic offline failover tests. */
    internal fun translateWithFallback(
        availabilityStore: ModelAvailabilityStore,
        requestedModel: String,
        now: Instant,
        elapsedMillis: () -> Long = { 0L },
        attempt: (model: String, timeoutMs: Int) -> GeminiClient.Result
    ): GeminiClient.Result {
        val selected = requestedModel.trim()
        if (!GeminiClient.isValidModelId(selected)) {
            return GeminiClient.Result.Failure.InvalidModel
        }

        var attempts = 0
        var lastTransientFailure: GeminiClient.Result.Failure? = null
        for (model in candidateModels(selected)) {
            if (attempts >= MAX_MODEL_ATTEMPTS) break
            if (availabilityStore.isUnavailable(model, now)) continue

            val remainingMs = TOTAL_BUDGET_MS - elapsedMillis().coerceAtLeast(0L)
            if (remainingMs < MIN_REMAINING_MS) break
            // Gemini 2.5 Flash works on the owner's account but may need
            // a little longer than Flash-Lite for a complete response.
            val perModelTimeout = if (model.equals("gemini-2.5-flash", ignoreCase = true))
                4_500 else PER_MODEL_TIMEOUT_MS
            val timeoutMs = minOf(perModelTimeout.toLong(), remainingMs).toInt()
            attempts++

            when (val result = attempt(model, timeoutMs)) {
                is GeminiClient.Result.Success -> {
                    availabilityStore.clear(model)
                    return result
                }

                is GeminiClient.Result.Failure.AllKeysExhausted -> {
                    rememberQuotaFailure(availabilityStore, model, now, result.lastQuotaDetail)
                    lastTransientFailure = result
                }

                is GeminiClient.Result.Failure.QuotaExceeded -> {
                    rememberQuotaFailure(availabilityStore, model, now, result)
                    lastTransientFailure = result
                }

                is GeminiClient.Result.Failure.HttpError -> {
                    when {
                        result.code == 503 -> {
                            // A server reporting sustained high demand is
                            // unlikely to recover before the next keystroke.
                            availabilityStore.markShortCooldown(model, now, 180L)
                            lastTransientFailure = result
                        }
                        result.code == 408 || result.code in 500..599 -> {
                            availabilityStore.markShortCooldown(model, now, 60L)
                            lastTransientFailure = result
                        }
                        // A known, previously supported model can disappear;
                        // try the next known model, never conceal arbitrary
                        // user-typed 404s or malformed requests.
                        result.code == 404 &&
                            HIDDEN_FALLBACK_MODELS.any { it.equals(model, ignoreCase = true) } -> {
                            availabilityStore.markShortCooldown(model, now, 180L)
                            lastTransientFailure = result
                        }
                        else -> return result // 400/401/403 etc. are NOT transient
                    }
                }

                GeminiClient.Result.Failure.Timeout -> {
                    availabilityStore.markShortCooldown(model, now, 30L)
                    lastTransientFailure = result
                }

                // Bad keys, local network failures, bad request shapes and
                // unexpected responses must remain visible, not cause a
                // storm of unnecessary model switches.
                else -> return result
            }
        }

        return lastTransientFailure
            ?: GeminiClient.Result.Failure.HttpError(
                503, "All configured text models are temporarily unavailable; retry shortly."
            )
    }

    internal fun candidateModels(requestedModel: String): List<String> =
        (listOf(requestedModel.trim()) + HIDDEN_FALLBACK_MODELS)
            .distinctBy { it.lowercase(Locale.ROOT) }

    private fun rememberQuotaFailure(
        store: ModelAvailabilityStore,
        model: String,
        now: Instant,
        detail: GeminiClient.Result.Failure.QuotaExceeded?
    ) {
        if (detail?.scope == GeminiClient.Result.Failure.QuotaScope.DAILY) {
            store.markDailyExhausted(model, now)
        } else {
            // An unknown 429 MUST NOT lock a model until the daily reset.
            store.markShortCooldown(model, now, detail?.retryAfterSeconds)
        }
    }
}
