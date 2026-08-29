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
 *
 * v1.7.0 rewrote the prompt body to be much more explicit about what must
 * be PRESERVED rather than "improved": exact meaning, grammatical number,
 * person, tense, any gender the source itself expresses, tone, and
 * formality; no invented context; no silent singular/plural flips; source
 * ambiguity kept as ambiguity when the target language allows it; proper
 * nouns, @mentions, URLs, numbers, emoji, and formatting left untouched
 * unless the translation itself requires a change. This was tightened
 * specifically because a translation model given only "translate this
 * naturally" instructions has room to over-localize — smoothing over
 * exactly the kind of detail (a plural the user meant as a plural, an
 * intentionally vague pronoun) that changes what the message actually
 * says. See README.md's changelog entry for this version for the full
 * rationale and the real-world complaint that prompted it.
 *
 * v1.7.1 corrected a wrong assumption baked into v1.7.0's gender handling:
 * text reaching the typed-trigger pipeline (`?en`, `?pl`, ...) is NOT
 * necessarily something the app owner wrote themselves — they may have
 * pasted someone else's message in and appended a trigger just to
 * translate it. [systemPromptFor] no longer tells Gemini "the author is
 * X" unconditionally whenever a gender is declared; it now also takes
 * [userPreferredLanguage] and instructs Gemini to apply the declared
 * gender to the speaker/author ONLY when the (already self-detected, per
 * the base prompt's own "detect it automatically" instruction) source
 * language matches the user's own preferred language AND the translation
 * target is a different language — i.e. only when the shape of the
 * request looks like "I wrote this myself, translate it out," never when
 * it looks like "someone sent me this in another language, translate it
 * in" or any other shape. See [systemPromptFor]'s own doc comment for the
 * full rule and README.md's changelog entry for this version for the
 * worked examples that motivated it.
 */
object TranslationPrompts {

    /** Not user-editable in v1 (this remains true post-v1.4.0) — the
     * wording is fixed and auditable. The target language name, the
     * speaker's declared gender, and (since v1.7.1) the user's own
     * preferred language are the only variables.
     *
     * [speakerGender] defaults to [UserGender.AUTO] (i.e. "say nothing
     * about it") so every existing call site that has no business knowing
     * the phone owner's gender — the long-press "translate a received
     * message" bubble, and the fixed non-sensitive "Test API" string in
     * SettingsActivity — keeps working unchanged. Only
     * [com.textgate.ai.accessibility.TextGateAccessibilityService.confirmAndProcess]
     * (the typed `?xx`-trigger path) is meant to ever pass
     * [UserGender.MALE] or [UserGender.FEMALE] here — see that function's
     * own comment.
     *
     * [userPreferredLanguage] is the app owner's own preferred/primary
     * language — [com.textgate.ai.security.AppSettingsStore.appInterfaceLanguage]
     * resolved to a concrete [SupportedLanguage], see
     * [com.textgate.ai.LocaleHelper.resolvePreferredLanguage] — and is only
     * ever consulted when [speakerGender] is not [UserGender.AUTO]: it is
     * the fact the "was this plausibly the user's own outgoing message, or
     * text they pasted in from someone else" rule below is checked
     * against. If a caller passes a non-[UserGender.AUTO] gender but
     * leaves this `null` (it should never happen — the one real caller
     * always resolves it first — but this function must still not guess),
     * the safe fallback is to omit the gender clause entirely, exactly as
     * if [UserGender.AUTO] had been passed: see the `?: return base` below.
     */
    fun systemPromptFor(
        target: TriggerDetector.Target,
        speakerGender: UserGender = UserGender.AUTO,
        userPreferredLanguage: SupportedLanguage? = null
    ): String {
        val name = (Languages.byCode(target.code) ?: Languages.DEFAULT).englishName

        val base = "Translate the provided text into $name.\n\n" +
            "Your highest priority is to preserve the source text's exact meaning and " +
            "communicative intent. The translation must not add, remove, strengthen, " +
            "weaken, reinterpret, summarize, or otherwise alter what the author is " +
            "saying.\n\n" +
            "Within that constraint, make the translation natural, idiomatic, and " +
            "conversational — as a native $name speaker would actually express the same " +
            "meaning. Do not translate mechanically word for word, but never sacrifice " +
            "meaning or intent for more natural or polished phrasing.\n\n" +
            "The source text may be in any language; detect it automatically. If it is " +
            "already written in $name, do not translate it. Only correct grammar, " +
            "spelling, punctuation, and phrasing where this can be done without changing " +
            "the meaning, intent, tone, or degree of certainty.\n\n" +
            "Preserve exactly:\n\n" +
            "- intended meaning and communicative intent,\n" +
            "- grammatical number (singular vs. plural),\n" +
            "- grammatical person,\n" +
            "- tense,\n" +
            "- any gender explicitly expressed or clearly implied by the source,\n" +
            "- tone and level of formality,\n" +
            "- degree of certainty, emphasis, criticism, politeness, and emotional " +
            "strength.\n\n" +
            "Never invent information, context, motivation, conclusions, or implications " +
            "that are not present in the source.\n\n" +
            "Never make a statement stronger or weaker than the original. Do not turn a " +
            "possibility into a certainty, a suggestion into a command, criticism into a " +
            "stronger accusation, or a neutral statement into an emotional one.\n\n" +
            "If the source is ambiguous and the target language allows the same " +
            "ambiguity, preserve it. Do not guess what the author probably meant and do " +
            "not resolve ambiguity merely to make the translation sound more polished.\n\n" +
            "You may correct or add basic punctuation such as commas and periods when " +
            "needed for readability. However, do not use punctuation to change or infer " +
            "communicative intent. In particular, do not turn an ambiguous statement into " +
            "a question, exclamation, command, or other sentence type unless that intent " +
            "is unambiguous from the source.\n\n" +
            "Never change singular to plural or plural to singular.\n\n" +
            "Keep unchanged, exactly as written, unless the translation itself requires a " +
            "change: proper nouns and names, usernames and @mentions, URLs and links, " +
            "numbers, emoji, and existing formatting or line breaks.\n\n" +
            "Return only the finished translated text. Do not include quotation marks, " +
            "labels, explanations, notes, alternative versions, or any other commentary " +
            "— nothing but the translation itself."

        // AUTO ("Automatyczna/nieokreślona") means the user deliberately
        // declared no preference — say nothing about gender at all, rather
        // than passing some default guess into the prompt.
        val genderWord = when (speakerGender) {
            UserGender.AUTO -> return base
            UserGender.MALE -> "male"
            UserGender.FEMALE -> "female"
        }

        // v1.7.1: text reaching this function is NOT necessarily something
        // the app owner wrote themselves — they may have pasted someone
        // else's message in and appended a trigger just to translate it
        // (see the class doc's v1.7.1 note for the real-world example that
        // exposed this). Without knowing the user's own preferred
        // language, the "does this look like the user's own outgoing
        // message" rule below cannot be evaluated at all — falling back to
        // the ungendered base prompt is the safe choice (never guess),
        // exactly like the AUTO branch above.
        val preferredName = userPreferredLanguage?.englishName ?: return base

        // The conditional rule below still deliberately leaves "does this
        // target language even grammatically mark speaker gender" to
        // Gemini itself, for the same reason as before v1.7.1: this app
        // supports 40 target languages, most of which do NOT grammatically
        // mark a first-person speaker's gender at all (English, Chinese,
        // Turkish, Finnish, ...), and hand-maintaining a per-language
        // lookup table would duplicate exactly the kind of unmaintainable,
        // error-prone list this object's own class doc already warns
        // against. What v1.7.1 adds on top is a second, independent gate —
        // "does this even look like the user's own message" — which is NOT
        // a linguistic judgment call the model needs to make; it is a
        // simple language-equality check this prompt gives Gemini the two
        // facts (preferred language, target language) to perform itself,
        // using the source-language detection the base prompt above
        // already asks for. No separate detection request is made for
        // this — it rides along on the one translation request.
        return base + "\n\n" +
            "The person using this translator has set their own gender as $genderWord, " +
            "and their preferred/primary language is $preferredName. Automatically " +
            "detect the source language of the text below, as already instructed. Treat " +
            "the declared gender as the AUTHOR's gender ONLY when both of the following " +
            "hold: the detected source language is $preferredName, AND the translation " +
            "target ($name) is a different language from $preferredName. In that case, " +
            "apply the declared gender only to grammatical forms that refer directly to " +
            "the speaker/author (for example first-person verb agreement, past-tense or " +
            "perfective participles, or adjectives describing the speaker — the kind of " +
            "form languages like Polish inflect for \"I was\"/\"tired\"). If the source " +
            "text is not in $preferredName, or the translation target is $preferredName " +
            "itself, do NOT apply the declared gender at all — text like that may have " +
            "been written by someone else and simply pasted in for translation, not by " +
            "the person using this translator. Regardless of whether it applies, never " +
            "use the declared gender to infer, assume, or change the gender of anyone " +
            "else mentioned in the text — every other person's gender, and their " +
            "grammatical number, must be preserved exactly as the source expresses it, " +
            "or left unspecified if the source does not specify it. If the source text " +
            "gives no gender information and the conditions above do not allow safely " +
            "applying the declared gender, do not guess — preserve the ambiguity, or use " +
            "the most natural gender-neutral construction the target language allows."
    }

    /** Kept for source compatibility with anything still constructing the
     * original two prompts directly (e.g. SettingsActivity's "Test API
     * connection" button, which always tests with a fixed, non-sensitive
     * string regardless of the user's chosen target language or gender
     * preference — [speakerGender] intentionally defaults to
     * [UserGender.AUTO] here). */
    val EN_TRANSLATION_SYSTEM_PROMPT: String get() = systemPromptFor(TriggerDetector.Target.ENGLISH)
    val PL_TRANSLATION_SYSTEM_PROMPT: String get() = systemPromptFor(TriggerDetector.Target.POLISH)
}
