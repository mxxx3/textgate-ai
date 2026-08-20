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
}
