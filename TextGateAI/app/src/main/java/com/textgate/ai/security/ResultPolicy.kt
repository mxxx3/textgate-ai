package com.textgate.ai.security

import com.textgate.ai.network.GeminiClient

/**
 * The single, named decision point for "is this Gemini result allowed to
 * overwrite the user's field." Kept as its own tiny, exhaustively tested
 * unit specifically so the property "on timeout / HTTP error / empty or
 * invalid response, the original text is left completely untouched" is a
 * locked-in, independently verifiable fact rather than something implied
 * by reading the accessibility service's control flow.
 */
object ResultPolicy {
    fun shouldReplaceText(result: GeminiClient.Result): Boolean = result is GeminiClient.Result.Success
}
