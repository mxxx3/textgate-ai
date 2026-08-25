package com.textgate.ai.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Talks to the Gemini `generateContent` REST endpoint using nothing but
 * [java.net.HttpsURLConnection] — no HTTP client library is used, which
 * means there is no third-party dependency in the app's request/response
 * path that could add its own logging, telemetry, or caching behavior
 * outside this file's control.
 *
 * This class is intentionally STATELESS: it takes the API key and model as
 * plain parameters on every call rather than caching them as fields, so a
 * decrypted key is only ever alive for the duration of a single call.
 *
 * Every method here MUST be called from a background thread — it performs
 * blocking network I/O. See TextGateAccessibilityService for the single
 * background executor that does so.
 *
 * Nothing in this file calls android.util.Log with the request body,
 * response body, headers, or API key, in debug or release builds.
 */
object GeminiClient {

    private const val ENDPOINT_TEMPLATE = "https://%s/v1beta/models/%s:generateContent"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Gemini's HTTP status for RESOURCE_EXHAUSTED (any quota kind — daily,
     * per-minute, or per-token). See [Result.Failure.QuotaScope] for how
     * this app tells those apart, and
     * [com.textgate.ai.network.TranslationOrchestrator] for what it does
     * with that distinction. */
    private const val HTTP_QUOTA_EXCEEDED = 429

    /** [AppSettingsStore.DEFAULT_MODEL] — this app's default specifically
     * for its generous free-tier quota, not for reasoning ability. A plain
     * translation is not a reasoning task, so there is no reason to pay
     * for, or wait on, "thinking" tokens on this model; see
     * [buildRequestBody]. Matched by exact (case-insensitive) model id
     * rather than a prefix/substring, so a possible future
     * "gemini-3.5-flash-lite-8b"-style variant is not silently swept in
     * without a deliberate decision. Left unset for every other model:
     * different model families expose different thinking-budget
     * ranges/defaults/requirements, and guessing a value for a model this
     * app doesn't specifically know about risks a rejected request rather
     * than a faster one. */
    private const val LOW_THINKING_BUDGET_MODEL = "gemini-3.5-flash-lite"

    /** Conservative allow-list for a Gemini model id: letters, digits, dot,
     * dash, underscore only. Rejects anything else before it is ever used
     * to build a URL, so a malformed/garbage "model" setting cannot result
     * in an unexpected request shape. */
    private val MODEL_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,100}$")

    fun isValidModelId(model: String): Boolean = MODEL_ID_REGEX.matches(model.trim())

    sealed class Result {
        data class Success(val translatedText: String) : Result()

        sealed class Failure : Result() {
            data object InvalidModel : Failure()
            data object MissingApiKey : Failure()
            data object Timeout : Failure()
            data object NetworkError : Failure()
            /** [detail] is a short, best-effort excerpt of Gemini's own
             * `error.message` from the response body (e.g. what exactly was
             * wrong with a 400 INVALID_ARGUMENT) — null if the body was
             * empty, unparseable, or carried no message field. Bounded to
             * [HTTP_ERROR_DETAIL_MAX_LENGTH] characters so a pathological
             * response body can't bloat a UI string. Never derived from
             * anything the user typed — this is Google's own diagnostic
             * text about the request shape/model/quota, not user content. */
            data class HttpError(val code: Int, val detail: String? = null) : Failure()
            data object EmptyResponse : Failure()
            data object InvalidResponse : Failure()
            /** The constructed request did not target the allow-listed
             * Gemini host. This should be unreachable; if it ever fires it
             * means a code change broke URL construction, and we refuse
             * to send the request rather than risk it. */
            data object HostNotAllowed : Failure()
            /** Every key in [com.textgate.ai.security.SecureApiKeyStore] was
             * tried once, in rotation, and each came back quota-exceeded —
             * see [com.textgate.ai.network.KeyRotationTranslator].
             * [translateBlocking] itself never produces this: it has no
             * concept of multiple keys and would report a single exhausted
             * key as [QuotaExceeded]. [lastQuotaDetail] is whichever key's
             * [QuotaExceeded] was observed LAST in rotation order — used by
             * [com.textgate.ai.network.TranslationOrchestrator] to decide
             * how to treat the whole model, not just the one key. */
            data class AllKeysExhausted(val lastQuotaDetail: QuotaExceeded? = null) : Failure()

            /** How narrowly [QuotaExceeded] was able to identify what kind
             * of quota a 429 response reported, from the response body's
             * own `error.details[].violations[]` (Google's standard
             * QuotaFailure error-detail shape) — see [parseQuotaFailure].
             * [UNKNOWN] is the safe default whenever the response body
             * cannot be parsed into an unambiguous answer: callers must
             * NEVER treat [UNKNOWN] as [DAILY] — see
             * [com.textgate.ai.network.TranslationOrchestrator]'s class doc
             * for why guessing "daily" from an ambiguous 429 is exactly the
             * failure mode this distinction exists to prevent. */
            enum class QuotaScope { DAILY, SHORT_TERM, UNKNOWN }

            /** A 429 (RESOURCE_EXHAUSTED) response. [scope] is this app's
             * best-effort read of WHICH quota was hit (see [QuotaScope]);
             * [retryAfterSeconds] is the server's own suggested wait, taken
             * from the `Retry-After` HTTP header or, failing that, a
             * `RetryInfo` error detail's `retryDelay` — null if neither was
             * present or parseable. */
            data class QuotaExceeded(val scope: QuotaScope, val retryAfterSeconds: Long?) : Failure()
        }
    }

    /**
     * Performs one blocking HTTPS request. Must be called off the main
     * thread. [userText] is the ONLY user-originated content included in
     * the request body — no package name, device identifier, model name of
     * the phone, or prior conversation history is ever attached.
     */
    fun translateBlocking(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String
    ): Result {
        if (apiKey.isBlank()) return Result.Failure.MissingApiKey
        val trimmedModel = model.trim()
        if (!isValidModelId(trimmedModel)) return Result.Failure.InvalidModel

        val url = try {
            URL(
                String.format(
                    ENDPOINT_TEMPLATE,
                    NetworkAllowlist.GEMINI_HOST,
                    URLEncoder.encode(trimmedModel, "UTF-8")
                )
            )
        } catch (_: Exception) {
            return Result.Failure.InvalidModel
        }

        // Defense in depth: refuse to proceed unless the URL we just built
        // targets exactly the allow-listed host over HTTPS.
        if (!NetworkAllowlist.isAllowedHost(url.host) || !url.protocol.equals("https", ignoreCase = true)) {
            return Result.Failure.HostNotAllowed
        }

        var connection: HttpsURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Header form (not a query-string key) so the key never
                // appears in server/proxy access logs or in this app's own
                // URL object.
                setRequestProperty("x-goog-api-key", apiKey)
            }

            val bodyBytes = buildRequestBody(systemPrompt, userText, trimmedModel).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(bodyBytes) }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                } catch (_: Exception) {
                    ""
                }
                if (statusCode == HTTP_QUOTA_EXCEEDED) {
                    val retryAfterHeader = connection.getHeaderField("Retry-After")
                    return parseQuotaFailure(errorBody, retryAfterHeader)
                }
                return Result.Failure.HttpError(statusCode, extractErrorMessage(errorBody))
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseResponse(responseText)
        } catch (e: Exception) {
            classifyException(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Maps a caught exception to a [Result.Failure]. Extracted as its own
     * `internal` function purely so its classification rules (timeout vs.
     * generic I/O vs. anything else) can be unit-tested directly and
     * deterministically, by constructing the exception types themselves,
     * rather than relying on a real socket actually timing out in a test
     * run.
     */
    internal fun classifyException(e: Exception): Result.Failure = when (e) {
        is SocketTimeoutException -> Result.Failure.Timeout
        is IOException -> Result.Failure.NetworkError
        else -> Result.Failure.InvalidResponse
    }

    /** [model] is the already-trimmed, already-validated model id (see
     * [translateBlocking]) — used ONLY to decide whether to attach a low
     * `thinkingConfig`, never echoed into the request body itself (the
     * model id is already part of the URL path, per the Gemini REST API
     * shape). */
    private fun buildRequestBody(systemPrompt: String, userText: String, model: String): JSONObject {
        val userContent = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText)))

        val systemInstruction = JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))

        val generationConfig = JSONObject()
            .put("temperature", 0.2)

        // See LOW_THINKING_BUDGET_MODEL's doc comment: a plain translation
        // gains nothing from "thinking" tokens, so explicitly request the
        // lowest available budget (0) for the one model this app knows
        // supports that field. Every other model is left exactly as
        // before — no generationConfig.thinkingConfig key at all — so this
        // is purely additive and cannot change behavior for any model
        // other than the default.
        if (model.equals(LOW_THINKING_BUDGET_MODEL, ignoreCase = true)) {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        return JSONObject()
            .put("contents", JSONArray().put(userContent))
            .put("systemInstruction", systemInstruction)
            .put("generationConfig", generationConfig)
    }

    /** `internal` (not `private`) solely so unit tests can exercise JSON
     * response parsing directly, without needing a real network call. */
    internal fun parseResponse(raw: String): Result {
        return try {
            val json = JSONObject(raw)
            val candidates = json.optJSONArray("candidates")
                ?: return Result.Failure.EmptyResponse
            if (candidates.length() == 0) return Result.Failure.EmptyResponse

            val content = candidates.getJSONObject(0).optJSONObject("content")
                ?: return Result.Failure.EmptyResponse
            val parts = content.optJSONArray("parts")
                ?: return Result.Failure.EmptyResponse

            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                builder.append(part.optString("text", ""))
            }

            val text = builder.toString().trim()
            if (text.isEmpty()) Result.Failure.EmptyResponse else Result.Success(text)
        } catch (_: Exception) {
            Result.Failure.InvalidResponse
        }
    }

    /**
     * Classifies a 429 response into a [Result.Failure.QuotaExceeded],
     * reading Google's standard `google.rpc.Status` error-detail shape:
     * `error.details[]` entries whose `@type` ends in `QuotaFailure` carry
     * a `violations[]` array, each with a `quotaId` and/or `quotaMetric`
     * string this app pattern-matches (case-insensitively, substring only —
     * deliberately tolerant of the exact metric-name spelling changing)
     * for "per day"/"daily" vs. "per minute"/"per second" wording. A
     * `RetryInfo`-typed detail's `retryDelay` field (a duration string like
     * "35s") is read as a fallback source for [retryAfterHeader] when the
     * HTTP header itself was absent.
     *
     * `internal` (not `private`) purely so this can be unit-tested directly
     * against hand-written response bodies, without a real 429 from Gemini
     * — the same reasoning as [classifyException] and [parseResponse].
     *
     * Any parse failure — malformed JSON, an unexpected shape, a future
     * wording change this app doesn't recognize — falls back to
     * [Result.Failure.QuotaScope.UNKNOWN] rather than throwing or guessing
     * [Result.Failure.QuotaScope.DAILY]. An ambiguous 429 must never be
     * allowed to block a model for a whole day.
     */
    internal fun parseQuotaFailure(errorBody: String, retryAfterHeader: String?): Result.Failure.QuotaExceeded {
        var scope = Result.Failure.QuotaScope.UNKNOWN
        var retryDelaySeconds: Long? = retryAfterHeader?.trim()?.toLongOrNull()

        try {
            val error = JSONObject(errorBody).optJSONObject("error")
            val details = error?.optJSONArray("details") ?: JSONArray()
            for (i in 0 until details.length()) {
                val detail = details.optJSONObject(i) ?: continue
                val type = detail.optString("@type", "")

                if (type.endsWith("QuotaFailure")) {
                    val violations = detail.optJSONArray("violations") ?: JSONArray()
                    for (j in 0 until violations.length()) {
                        val violation = violations.optJSONObject(j) ?: continue
                        val combined = (
                            violation.optString("quotaId", "") + " " +
                                violation.optString("quotaMetric", "") + " " +
                                violation.optString("description", "")
                            ).lowercase()
                        val violationScope = classifyQuotaText(combined)
                        // DAILY always wins over SHORT_TERM/UNKNOWN, so a
                        // response reporting several simultaneous
                        // violations is never under-classified.
                        if (violationScope == Result.Failure.QuotaScope.DAILY) {
                            scope = Result.Failure.QuotaScope.DAILY
                        } else if (violationScope == Result.Failure.QuotaScope.SHORT_TERM &&
                            scope != Result.Failure.QuotaScope.DAILY
                        ) {
                            scope = Result.Failure.QuotaScope.SHORT_TERM
                        }
                    }
                }

                if (type.endsWith("RetryInfo") && retryDelaySeconds == null) {
                    retryDelaySeconds = parseRetryDelaySeconds(detail.optString("retryDelay", ""))
                }
            }
        } catch (_: Exception) {
            // Unexpected/unparseable body: keep whatever was determined so
            // far (UNKNOWN scope, header-only retry delay) rather than
            // guess further.
        }

        return Result.Failure.QuotaExceeded(scope, retryDelaySeconds)
    }

    private fun classifyQuotaText(text: String): Result.Failure.QuotaScope = when {
        text.contains("perday") || text.contains("per_day") || text.contains("per day") ||
            text.contains("daily") -> Result.Failure.QuotaScope.DAILY
        text.contains("perminute") || text.contains("per_minute") || text.contains("per minute") ||
            text.contains("persecond") || text.contains("per_second") || text.contains("per second") ->
            Result.Failure.QuotaScope.SHORT_TERM
        else -> Result.Failure.QuotaScope.UNKNOWN
    }

    /** Parses a duration string like `"35s"` or `"1.500s"` (the format
     * Google APIs use for `google.protobuf.Duration`-derived `retryDelay`
     * fields) into whole seconds, rounding down. Returns null for anything
     * that doesn't parse, rather than a guessed default — the caller
     * ([parseQuotaFailure]) already has its own safe fallback for that. */
    private fun parseRetryDelaySeconds(raw: String): Long? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim().removeSuffix("s")
        return trimmed.toDoubleOrNull()?.toLong()
    }

    /** See [Result.Failure.HttpError.detail]. */
    private const val HTTP_ERROR_DETAIL_MAX_LENGTH = 200

    /** Best-effort extraction of Google's own `error.message` from a
     * non-2xx response body (the standard `{"error":{"code":...,
     * "message":"...","status":"..."}}` shape every Generative Language API
     * error uses, not just 429s). Returns null rather than throwing for any
     * body that isn't that exact shape — this is a diagnostics nicety, never
     * something the rest of the request/response path depends on. */
    internal fun extractErrorMessage(errorBody: String): String? {
        if (errorBody.isBlank()) return null
        return try {
            val message = JSONObject(errorBody).optJSONObject("error")?.optString("message")
            message?.trim()?.takeIf { it.isNotEmpty() }?.take(HTTP_ERROR_DETAIL_MAX_LENGTH)
        } catch (_: Exception) {
            null
        }
    }
}
