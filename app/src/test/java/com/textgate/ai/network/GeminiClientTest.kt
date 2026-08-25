package com.textgate.ai.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Covers spec scenarios:
 *   #12 API timeout -> classified distinctly, never crashes, never retries silently
 *   #13 API error -> classified distinctly; response parsing failures never
 *       produce a Success
 *
 * translateBlocking() itself is not exercised against a real Gemini
 * endpoint here — this project intentionally does not weaken
 * NetworkAllowlist / the hard-coded host check to make room for a test
 * double, since that check is itself a production security control. The
 * two behaviors it composes — input validation, and exception ->
 * Failure classification, and response-body -> Result parsing — are each
 * fully covered directly below without requiring any real network call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeminiClientTest {

    @Test
    fun `valid model ids are accepted`() {
        assertTrue(GeminiClient.isValidModelId("gemini-2.5-flash"))
        assertTrue(GeminiClient.isValidModelId("gemini-3.7-flash"))
        assertTrue(GeminiClient.isValidModelId("gemini_2.5.pro"))
    }

    @Test
    fun `model ids with unexpected characters are rejected`() {
        assertFalse(GeminiClient.isValidModelId("gemini 2.5 flash"))
        assertFalse(GeminiClient.isValidModelId("../../etc/passwd"))
        assertFalse(GeminiClient.isValidModelId("model?evil=1"))
        assertFalse(GeminiClient.isValidModelId(""))
    }

    @Test
    fun `blank api key is rejected before any network attempt`() {
        val result = GeminiClient.translateBlocking(
            apiKey = "",
            model = "gemini-2.5-flash",
            systemPrompt = "system",
            userText = "hello"
        )
        assertTrue(result is GeminiClient.Result.Failure.MissingApiKey)
    }

    @Test
    fun `invalid model id is rejected before any network attempt`() {
        val result = GeminiClient.translateBlocking(
            apiKey = "fake-key",
            model = "not a valid model!!",
            systemPrompt = "system",
            userText = "hello"
        )
        assertTrue(result is GeminiClient.Result.Failure.InvalidModel)
    }

    @Test
    fun `well-formed response is parsed into Success`() {
        val json = """
            {"candidates":[{"content":{"parts":[{"text":"Let me know when you get a chance."}]}}]}
        """.trimIndent()
        val result = GeminiClient.parseResponse(json)
        val success = result as GeminiClient.Result.Success
        assertEquals("Let me know when you get a chance.", success.translatedText)
    }

    @Test
    fun `response with no candidates is EmptyResponse`() {
        val result = GeminiClient.parseResponse("""{"candidates":[]}""")
        assertTrue(result is GeminiClient.Result.Failure.EmptyResponse)
    }

    @Test
    fun `malformed json is InvalidResponse`() {
        val result = GeminiClient.parseResponse("not json at all {{{")
        assertTrue(result is GeminiClient.Result.Failure.InvalidResponse)
    }

    @Test
    fun `blank translated text is treated as EmptyResponse`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"   "}]}}]}"""
        val result = GeminiClient.parseResponse(json)
        assertTrue(result is GeminiClient.Result.Failure.EmptyResponse)
    }

    @Test
    fun `socket timeout is classified as Timeout`() {
        val result = GeminiClient.classifyException(SocketTimeoutException("timed out"))
        assertTrue(result is GeminiClient.Result.Failure.Timeout)
    }

    @Test
    fun `generic IO exception is classified as NetworkError`() {
        val result = GeminiClient.classifyException(IOException("connection reset"))
        assertTrue(result is GeminiClient.Result.Failure.NetworkError)
    }

    @Test
    fun `unexpected exception is classified as InvalidResponse, never crashes`() {
        val result = GeminiClient.classifyException(IllegalStateException("unexpected"))
        assertTrue(result is GeminiClient.Result.Failure.InvalidResponse)
    }

    // --- parseQuotaFailure: RPD vs. RPM/TPM vs. unrecognized 429 bodies ---

    @Test
    fun `quota body naming a per-day violation is classified DAILY`() {
        val body = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                    {"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaMetric":"generativelanguage.googleapis.com/generate_requests"}
                ]}
            ]}}
        """.trimIndent()
        val result = GeminiClient.parseQuotaFailure(body, retryAfterHeader = null)
        assertEquals(GeminiClient.Result.Failure.QuotaScope.DAILY, result.scope)
    }

    @Test
    fun `quota body naming a per-minute violation is classified SHORT_TERM, never DAILY`() {
        val body = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                    {"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}
                ]}
            ]}}
        """.trimIndent()
        val result = GeminiClient.parseQuotaFailure(body, retryAfterHeader = null)
        assertEquals(GeminiClient.Result.Failure.QuotaScope.SHORT_TERM, result.scope)
    }

    @Test
    fun `a daily violation among several never gets demoted to SHORT_TERM`() {
        val body = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                    {"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"},
                    {"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}
                ]}
            ]}}
        """.trimIndent()
        val result = GeminiClient.parseQuotaFailure(body, retryAfterHeader = null)
        assertEquals(GeminiClient.Result.Failure.QuotaScope.DAILY, result.scope)
    }

    @Test
    fun `unparseable or unrecognized 429 body falls back to UNKNOWN, never DAILY`() {
        assertEquals(
            GeminiClient.Result.Failure.QuotaScope.UNKNOWN,
            GeminiClient.parseQuotaFailure("not json at all {{{", retryAfterHeader = null).scope
        )
        assertEquals(
            GeminiClient.Result.Failure.QuotaScope.UNKNOWN,
            GeminiClient.parseQuotaFailure("""{"error":{"code":429}}""", retryAfterHeader = null).scope
        )
    }

    @Test
    fun `Retry-After header is used when present`() {
        val result = GeminiClient.parseQuotaFailure("""{"error":{}}""", retryAfterHeader = "42")
        assertEquals(42L, result.retryAfterSeconds)
    }

    @Test
    fun `RetryInfo detail's retryDelay is used when no header is present`() {
        val body = """
            {"error":{"details":[
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"35s"}
            ]}}
        """.trimIndent()
        val result = GeminiClient.parseQuotaFailure(body, retryAfterHeader = null)
        assertEquals(35L, result.retryAfterSeconds)
    }

    @Test
    fun `header takes priority over RetryInfo detail when both are present`() {
        val body = """
            {"error":{"details":[
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"35s"}
            ]}}
        """.trimIndent()
        val result = GeminiClient.parseQuotaFailure(body, retryAfterHeader = "10")
        assertEquals(10L, result.retryAfterSeconds)
    }

    @Test
    fun `no retry hint anywhere leaves retryAfterSeconds null`() {
        val result = GeminiClient.parseQuotaFailure("""{"error":{}}""", retryAfterHeader = null)
        assertEquals(null, result.retryAfterSeconds)
    }

    @Test
    fun `extractErrorMessage reads Google's standard error message shape`() {
        val body = """{"error":{"code":400,"message":"Invalid JSON payload received.","status":"INVALID_ARGUMENT"}}"""
        assertEquals("Invalid JSON payload received.", GeminiClient.extractErrorMessage(body))
    }

    @Test
    fun `extractErrorMessage returns null for an empty body`() {
        assertEquals(null, GeminiClient.extractErrorMessage(""))
    }

    @Test
    fun `extractErrorMessage returns null for malformed json, never throws`() {
        assertEquals(null, GeminiClient.extractErrorMessage("not json at all"))
    }

    @Test
    fun `extractErrorMessage returns null when the error object has no message field`() {
        assertEquals(null, GeminiClient.extractErrorMessage("""{"error":{"code":400}}"""))
    }

    @Test
    fun `extractErrorMessage truncates a very long message`() {
        val longMessage = "x".repeat(500)
        val body = """{"error":{"message":"$longMessage"}}"""
        val result = GeminiClient.extractErrorMessage(body)
        assertEquals(200, result?.length)
    }
}
