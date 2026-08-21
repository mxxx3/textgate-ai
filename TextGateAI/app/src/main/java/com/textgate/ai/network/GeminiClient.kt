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
            data class HttpError(val code: Int) : Failure()
            data object EmptyResponse : Failure()
            data object InvalidResponse : Failure()
            /** The constructed request did not target the allow-listed
             * Gemini host. This should be unreachable; if it ever fires it
             * means a code change broke URL construction, and we refuse
             * to send the request rather than risk it. */
            data object HostNotAllowed : Failure()
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

            val bodyBytes = buildRequestBody(systemPrompt, userText).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(bodyBytes) }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                return Result.Failure.HttpError(statusCode)
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

    private fun buildRequestBody(systemPrompt: String, userText: String): JSONObject {
        val userContent = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText)))

        val systemInstruction = JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))

        val generationConfig = JSONObject()
            .put("temperature", 0.2)

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
}
