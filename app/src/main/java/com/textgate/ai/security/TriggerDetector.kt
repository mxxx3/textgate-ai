package com.textgate.ai.security

/**
 * Detects and extracts a translation trigger from the current full text of
 * a field. Two triggers are supported, each matched as an exact,
 * case-sensitive suffix — the spec is explicit that a trigger must sit
 * exactly at the end of the current text, so no fuzzy matching, trimming,
 * or case-folding is performed here:
 *   - "?en" — translate the preceding text into English.
 *   - "?pl" — translate the preceding text into Polish.
 * Both triggers are the same fixed length, so a given piece of text can
 * only ever end with one of them at a time; there is no ambiguity to
 * resolve between them.
 *
 * This object never performs any I/O and never reads an AccessibilityNodeInfo
 * itself — it operates purely on a String the caller has already obtained
 * (after that caller has passed the SensitiveInputGuard checks).
 */
object TriggerDetector {

    /** Which language the matched trigger asks the text to be translated into. */
    enum class Target {
        ENGLISH,
        POLISH
    }

    const val TRIGGER_EN: String = "?en"
    const val TRIGGER_PL: String = "?pl"

    /** Kept for source compatibility with existing callers/tests that only
     * ever cared about the original English trigger. */
    const val TRIGGER: String = TRIGGER_EN

    private val TRIGGERS: Map<String, Target> = linkedMapOf(
        TRIGGER_EN to Target.ENGLISH,
        TRIGGER_PL to Target.POLISH
    )

    const val MAX_INPUT_LENGTH: Int = 4000

    sealed class Outcome {
        /** The text does not end with any recognized trigger. Do nothing. */
        data object NoTrigger : Outcome()

        /** The text ends with a trigger but there is nothing before it. */
        data object EmptyContent : Outcome()

        /** Text before the trigger exceeds [MAX_INPUT_LENGTH]; not sent automatically. */
        data class TooLong(val length: Int, val limit: Int) : Outcome()

        /** A trigger matched and content is within limits — safe to send. */
        data class Ready(val content: String, val target: Target) : Outcome()
    }

    /**
     * Evaluates [fullText] (the complete, current text of the field) and
     * returns what — if anything — should happen. Ambiguous or missing
     * input (null, doesn't end with a recognized trigger) always resolves
     * to [Outcome.NoTrigger], never to [Outcome.Ready].
     */
    fun detect(fullText: CharSequence?): Outcome {
        if (fullText.isNullOrEmpty()) return Outcome.NoTrigger
        val text = fullText.toString()

        val (trigger, target) = TRIGGERS.entries.firstOrNull { (trigger, _) ->
            text.endsWith(trigger)
        }?.toPair() ?: return Outcome.NoTrigger

        val content = text.substring(0, text.length - trigger.length)
        if (content.isBlank()) return Outcome.EmptyContent
        if (content.length > MAX_INPUT_LENGTH) return Outcome.TooLong(content.length, MAX_INPUT_LENGTH)

        return Outcome.Ready(content, target)
    }
}
