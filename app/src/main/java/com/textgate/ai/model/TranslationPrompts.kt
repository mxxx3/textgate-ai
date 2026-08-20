package com.textgate.ai.model

/**
 * The fixed system prompts sent alongside the user's text, one per
 * supported trigger (see TriggerDetector.Target). Kept as constants (not
 * user-editable in v1) so behavior is predictable and auditable.
 */
object TranslationPrompts {
    const val EN_TRANSLATION_SYSTEM_PROMPT: String =
        "Translate the provided Polish text into natural, conversational English. " +
            "Preserve the intended meaning, context, emotional tone, and level of formality. " +
            "Do not translate literally when a native English speaker would use a different " +
            "expression. Prefer idiomatic everyday English. Do not add information that is not " +
            "present in the original. Return only the translated text without quotation marks, " +
            "explanations, labels, or commentary."

    const val PL_TRANSLATION_SYSTEM_PROMPT: String =
        "Translate the provided text into natural, conversational Polish. The source text may " +
            "be in any language — detect it automatically; if it is already Polish, lightly " +
            "polish the phrasing rather than leaving it unchanged. Preserve the intended " +
            "meaning, context, emotional tone, and level of formality. Do not translate " +
            "literally when a native Polish speaker would use a different expression. Prefer " +
            "idiomatic everyday Polish. Do not add information that is not present in the " +
            "original. Return only the translated text without quotation marks, explanations, " +
            "labels, or commentary."
}
