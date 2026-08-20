package com.textgate.ai.model

/**
 * The fixed system prompt sent alongside the user's text for the "?en"
 * trigger. Kept as a single constant (not user-editable in v1) so its
 * behavior is predictable and auditable.
 */
object TranslationPrompts {
    const val EN_TRANSLATION_SYSTEM_PROMPT: String =
        "Translate the provided Polish text into natural, conversational English. " +
            "Preserve the intended meaning, context, emotional tone, and level of formality. " +
            "Do not translate literally when a native English speaker would use a different " +
            "expression. Prefer idiomatic everyday English. Do not add information that is not " +
            "present in the original. Return only the translated text without quotation marks, " +
            "explanations, labels, or commentary."
}
