package com.textgate.ai.security

import com.textgate.ai.model.Languages

/**
 * Detects and extracts a translation trigger from the current full text of
 * a field. One trigger is generated per language in [Languages.ALL] (40 as
 * of v1.4.0 — see that object for the full list and the reasoning behind
 * each language's code), matched at the end of the current text — a
 * trigger must sit at the very end, so no matching mid-sentence is
 * performed here. For example "?en" translates the preceding text into
 * English, "?de" into German, "?pt-rBR" into Brazilian Portuguese, and so
 * on for every code in [Languages.ALL].
 *
 * Several narrow, deliberate tolerances exist around the literal suffix, all
 * added after real on-device use surfaced them — some Android keyboards'
 * "automatic spacing" feature inserts a space right after typing "?" (since
 * "?" normally ends a sentence), a trailing space is often left after
 * finishing a word before the user can act on the trigger, and — because
 * keyboards treat the space right after "?" as the start of a brand-new
 * sentence — the letter that follows it routinely gets auto-capitalized too:
 *   - an OPTIONAL single space between "?" and the language code ("?en" or
 *     "? en" both match; "?  en" with two spaces does not — this stays a
 *     tolerance for one specific, observed keyboard behavior, not general
 *     whitespace fuzzing)
 *   - any number of trailing spaces after the language code
 *   - the language code's own CASE is ignored ("?en", "?En", "?EN" all
 *     match) — added specifically because the internal-space tolerance
 *     above creates exactly the "new sentence" situation a keyboard's
 *     auto-capitalization reacts to, so "? en" on a real device routinely
 *     arrives as "? En"
 * None of these tolerances change what gets sent: [Outcome.Ready.content] is
 * always exactly the text before the "?", so nothing the keyboard added
 * around the trigger itself ever ends up in the translated text. The "?"
 * itself is still required literally, and the trigger must still be the
 * last non-space thing in the field — nothing after it but optional spaces
 * is tolerated.
 *
 * This object never performs any I/O and never reads an AccessibilityNodeInfo
 * itself — it operates purely on a String the caller has already obtained
 * (after that caller has passed the SensitiveInputGuard checks).
 */
object TriggerDetector {

    /**
     * Which language the matched trigger asks the text to be translated
     * into — a thin wrapper around a [Languages] code rather than a fixed
     * enum, so any language in [Languages.ALL] can be a target without
     * this type needing to change. [ENGLISH] and [POLISH] are kept as
     * named constants purely for source compatibility with existing
     * callers/tests written before multi-language support (v1.4.0) — they
     * are ordinary [Target] values equal to `Target("en")`/`Target("pl")`,
     * nothing more special than any other language's `Target`.
     */
    @JvmInline
    value class Target(val code: String) {
        companion object {
            val ENGLISH = Target("en")
            val POLISH = Target("pl")
        }
    }

    /** Canonical (no-space) form of the original two triggers — kept for
     * source compatibility with existing callers/tests that only ever
     * cared about them. Matching itself goes through [TRIGGER_PATTERNS],
     * generated from [Languages.ALL], which also accepts the tolerances
     * described above for every supported language, not just these two. */
    const val TRIGGER_EN: String = "?en"
    const val TRIGGER_PL: String = "?pl"

    /** Kept for source compatibility with existing callers/tests that only
     * ever cared about the original English trigger. */
    const val TRIGGER: String = TRIGGER_EN

    /**
     * `\z` (not `$`) is used deliberately: `$` in Java/Kotlin regex also
     * matches just before a trailing line terminator, which would let a
     * field ending in "?en\n" count as triggered. `\z` accepts only the
     * true, absolute end of the text — the same "trigger must be the very
     * last thing typed" guarantee the original exact-suffix check had.
     *
     * [RegexOption.IGNORE_CASE] is scoped to exactly the language code
     * itself (via [Regex.escape], so a code containing "-" like "pt-rBR"
     * is matched literally, not as a regex range) — the same tolerance the
     * original two hand-written patterns had, now generated for every
     * language in [Languages.ALL] instead of just two. There is no
     * ambiguity between codes that share a prefix (e.g. "pt" vs.
     * "pt-rBR"): each pattern requires the code to be followed only by
     * optional trailing spaces before the absolute end of the text, so a
     * field ending in "?pt-rbr" cannot also satisfy the plain "pt"
     * pattern.
     */
    private val TRIGGER_PATTERNS: List<Pair<Regex, Target>> = Languages.ALL.map { language ->
        Regex(
            "\\?[ ]?${Regex.escape(language.code)}[ ]*\\z",
            RegexOption.IGNORE_CASE
        ) to Target(language.code)
    }

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

        val (match, target) = TRIGGER_PATTERNS.firstNotNullOfOrNull { (pattern, target) ->
            pattern.find(text)?.let { it to target }
        } ?: return Outcome.NoTrigger

        val content = text.substring(0, match.range.first)
        if (content.isBlank()) return Outcome.EmptyContent
        if (content.length > MAX_INPUT_LENGTH) return Outcome.TooLong(content.length, MAX_INPUT_LENGTH)

        return Outcome.Ready(content, target)
    }
}
