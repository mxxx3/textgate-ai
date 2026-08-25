package com.textgate.ai.network

import com.textgate.ai.security.SecureApiKeyStore

/**
 * Thin orchestration layer on top of [GeminiClient.translateBlocking]:
 * calls it once with the caller's currently active stored key; if that
 * request comes back quota-exceeded (HTTP 429 — Gemini's response for a
 * key that has hit its free-tier request limit), advances
 * [SecureApiKeyStore] to the next stored key and retries, up to once per
 * stored key. This lets a single user action (a typed trigger or a
 * long-press) transparently succeed as long as ANY of the user's saved
 * keys still has quota left, without the user having to notice a failure
 * or manually switch keys.
 *
 * [GeminiClient] itself is deliberately left untouched and stateless — it
 * has no concept of "more than one key" and is not the place to add one;
 * this object is the only thing that knows about rotation, keeping
 * [GeminiClient]'s existing, exhaustively-tested single-request behavior
 * exactly as it was.
 *
 * Only a quota response (429, surfaced as [GeminiClient.Result.Failure.
 * QuotaExceeded]) triggers a retry with a different key. Any other failure
 * (timeout, network error, a malformed model id, an empty or invalid
 * response, ...) is returned immediately, unrotated — those failures are
 * not fixed by trying a different key, and retrying them silently would
 * just delay the user seeing the real error.
 *
 * Must be called from a background thread — delegates directly to
 * [GeminiClient.translateBlocking], which performs blocking network I/O,
 * and to [SecureApiKeyStore], which performs Keystore-backed decryption
 * (CPU-bound but not free) on every attempt.
 *
 * This object has no concept of "model fallback" — it retries the SAME
 * [model] across different keys, nothing more. Automatically switching to
 * a different MODEL when every key is exhausted on this one is
 * [com.textgate.ai.network.TranslationOrchestrator]'s job, layered on top
 * of this object rather than mixed into it, so each piece stays
 * independently reasoned about: this one "is there any key left that can
 * serve this exact model," that one "which model should even be asked."
 */
object KeyRotationTranslator {

    fun translateWithRotation(
        apiKeyStore: SecureApiKeyStore,
        model: String,
        systemPrompt: String,
        userText: String
    ): GeminiClient.Result {
        val totalKeys = apiKeyStore.keyCount()
        if (totalKeys == 0) return GeminiClient.Result.Failure.MissingApiKey

        var lastQuotaDetail: GeminiClient.Result.Failure.QuotaExceeded? = null

        // At most one attempt per stored key, so a persistently-bad key
        // (revoked, malformed, or a Keystore decryption failure affecting
        // every key at once) can never spin longer than the list is long.
        repeat(totalKeys) {
            val apiKey = apiKeyStore.getActiveKeyPlaintext()
                ?: return GeminiClient.Result.Failure.MissingApiKey

            val result = GeminiClient.translateBlocking(
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                userText = userText
            )

            if (result !is GeminiClient.Result.Failure.QuotaExceeded) {
                return result
            }
            lastQuotaDetail = result
            apiKeyStore.advanceActiveKey()
        }

        return GeminiClient.Result.Failure.AllKeysExhausted(lastQuotaDetail)
    }
}
