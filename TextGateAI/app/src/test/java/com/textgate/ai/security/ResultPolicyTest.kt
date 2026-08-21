package com.textgate.ai.security

import com.textgate.ai.network.GeminiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers spec scenarios:
 *   #12 "API timeout -> original text stays untouched"
 *   #13 "API error -> original text stays untouched"
 *
 * by exhaustively proving that ResultPolicy.shouldReplaceText — the only
 * place TextGateAccessibilityService consults before ever calling
 * ACTION_SET_TEXT — returns true for Success and false for every single
 * Failure variant.
 */
class ResultPolicyTest {

    @Test
    fun `success allows replacing text`() {
        assertTrue(ResultPolicy.shouldReplaceText(GeminiClient.Result.Success("hello")))
    }

    @Test
    fun `every failure variant forbids replacing text`() {
        val failures = listOf(
            GeminiClient.Result.Failure.Timeout,
            GeminiClient.Result.Failure.NetworkError,
            GeminiClient.Result.Failure.HttpError(500),
            GeminiClient.Result.Failure.HttpError(401),
            GeminiClient.Result.Failure.EmptyResponse,
            GeminiClient.Result.Failure.InvalidResponse,
            GeminiClient.Result.Failure.InvalidModel,
            GeminiClient.Result.Failure.MissingApiKey,
            GeminiClient.Result.Failure.HostNotAllowed
        )
        failures.forEach { failure ->
            assertFalse(
                "Expected shouldReplaceText(false) for $failure",
                ResultPolicy.shouldReplaceText(failure)
            )
        }
    }
}
