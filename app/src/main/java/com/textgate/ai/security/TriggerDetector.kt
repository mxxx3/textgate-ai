package com.textgate.ai.security

/**
 * Detects and extracts the "?en" trigger from the current full text of a
 * field. This is the ONLY trigger supported today, matched as an exact,
 * case-sensitive suffix — the spec is explicit that the trigger must sit
 * exactly at the end of the current text, so no fuzzy matching, trimming,
 * or case-folding is performed here.
 *
 * This object never performs any I/O and never reads an AccessibilityNodeInfo
 * itself — it operates purely on a String the caller has already obtained
 * (after that caller has passed the SensitiveInputGuard checks).
 */
object TriggerDetector {

    const val TRIGGER: String = "?en"
    const val MAX_INPUT_LENGTH: Int = 4000

    sealed class Outcome {
        /** The text does not end with the trigger. Do nothing. */
        data object NoTrigger : Outcome()

        /** The text ends with the trigger but there is nothing before it. */
        data object EmptyContent : Outcome()

        /** Text before the trigger exceeds [MAX_INPUT_LENGTH]; not sent automatically. */
        data class TooLong(val length: Int, val limit: Int) : Outcome()

        /** Trigger matched and content is within limits — safe to send. */
        data class Ready(val content: String) : Outcome()
    }

    /**
     * Evaluates [fullText] (the complete, current text of the field) and
     * returns what — if anything — should happen. Ambiguous or missing
     * input (null, doesn't end with the trigger) always resolves to
     * [Outcome.NoTrigger], never to [Outcome.Ready].
     */
    fun detect(fullText: CharSequence?): Outcome {
        if (fullText.isNullOrEmpty()) return Outcome.NoTrigger
        val text = fullText.toString()

        if (!text.endsWith(TRIGGER)) return Outcome.NoTrigger

        val content = text.substring(0, text.length - TRIGGER.length)
        if (content.isBlank()) return Outcome.EmptyContent
        if (content.length > MAX_INPUT_LENGTH) return Outcome.TooLong(content.length, MAX_INPUT_LENGTH)

        return Outcome.Ready(content)
    }
}
