package com.textgate.ai.model

import com.textgate.ai.security.TriggerDetector

/**
 * The system prompt sent alongside the user's text for every translation
 * request, for both pipelines (typed `?xx` triggers and the long-press
 * bubble). Prior to v1.4.0 this was two hand-written constants, one for
 * English and one for Polish — the only two languages TextGate AI
 * supported at the time. Now that [Languages.ALL] lists 40 languages, one
 * near-duplicate constant per language would be unmaintainable (and error
 * -prone: any future wording tweak would need to be copy-pasted 40 times
 * and kept in sync by hand). [systemPromptFor] generates the same prompt
 * shape for any of them instead.
 *
 * [SupportedLanguage.englishName] (not [SupportedLanguage.nativeName]) is
 * used inside the generated prompt text deliberately: an instruction
 * written in one consistent language (English) reads more reliably to a
 * translation model than a sentence that switches script mid-way (e.g.
 * "...into natural, conversational 日本語." mixing Latin and CJK script for
 * no functional reason). This does not affect what script the *response*
 * comes back in — Gemini still returns the translation in the target
 * language's own script, exactly as it did for the original two
 * hand-written prompts.
 */
object TranslationPrompts {

    /** Not user-editable in v1 (this remains true post-v1.4.0) — the
     * wording is fixed and auditable, only the target language name is a
     * variable. */
    fun systemPromptFor(target: TriggerDetector.Target): String {
        val name = (Languages.byCode(target.code) ?: Languages.DEFAULT).englishName
        return "Translate the provided text into natural, conversational $name. " +
            "The source text may be in any language — detect it automatically; if it is " +
            "already $name, lightly polish the phrasing rather than leaving it unchanged. " +
            "Preserve the intended meaning, context, emotional tone, and level of formality. " +
            "Do not translate literally when a native $name speaker would use a different " +
            "expression. Prefer idiomatic everyday $name. Do not add information that is not " +
            "present in the original. Return only the translated text without quotation marks, " +
            "explanations, labels, or commentary."
    }

    /** Kept for source compatibility with anything still constructing the
     * original two prompts directly (e.g. SettingsActivity's "Test API
     * connection" button, which always tests with a fixed, non-sensitive
     * string regardless of the user's chosen target language). */
    val EN_TRANSLATION_SYSTEM_PROMPT: String get() = systemPromptFor(TriggerDetector.Target.ENGLISH)
    val PL_TRANSLATION_SYSTEM_PROMPT: String get() = systemPromptFor(TriggerDetector.Target.POLISH)
}
