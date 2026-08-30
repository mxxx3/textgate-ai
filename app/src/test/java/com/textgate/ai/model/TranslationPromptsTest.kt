package com.textgate.ai.model

import com.textgate.ai.security.TriggerDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the v1.7.0 system-prompt rewrite (what MUST always be preserved,
 * what must never be invented) and the v1.7.1 correction to gender
 * handling: a declared [UserGender] is now woven in only when the request
 * plausibly looks like the user's OWN outgoing message — never assumed
 * just because a gender was declared somewhere in Settings, since the
 * typed-trigger pipeline can just as easily be translating text the user
 * pasted in from someone else (see TranslationPrompts' own class doc for
 * the worked example that prompted this).
 *
 * Pure string-content assertions; no network call, no Gemini response
 * involved — this only proves what gets SENT, not how a model responds to
 * it.
 */
class TranslationPromptsTest {

    private val english = TriggerDetector.Target.ENGLISH
    private val polish = TriggerDetector.Target.POLISH

    /** Present only in the appended gender clause — absent from the base
     * prompt — used throughout as "does this prompt carry a gender clause
     * at all" without depending on the clause's exact wording. */
    private fun hasGenderClause(prompt: String) = prompt.contains("has set their own gender as")

    @Test
    fun `AUTO gender adds no speaker-declared-gender clause`() {
        val prompt = TranslationPrompts.systemPromptFor(english, UserGender.AUTO, Languages.DEFAULT)
        assertFalse(hasGenderClause(prompt))
    }

    @Test
    fun `omitting the gender argument defaults to AUTO (no gender clause)`() {
        // The long-press bubble path and the "Test API" fixed string both
        // rely on this default — see each call site's own comment.
        val withDefault = TranslationPrompts.systemPromptFor(english)
        val withExplicitAuto = TranslationPrompts.systemPromptFor(english, UserGender.AUTO)
        assertTrue(withDefault == withExplicitAuto)
        assertFalse(hasGenderClause(withDefault))
    }

    @Test
    fun `non-AUTO gender WITHOUT a preferred language falls back to the ungendered base prompt`() {
        // This should never actually happen from the app's one real caller
        // (TextGateAccessibilityService.confirmAndProcess always resolves
        // a preferred language first), but the function itself must not
        // guess if it ever does.
        val withoutPreferred = TranslationPrompts.systemPromptFor(english, UserGender.MALE)
        val autoBaseline = TranslationPrompts.systemPromptFor(english, UserGender.AUTO)
        assertFalse(hasGenderClause(withoutPreferred))
        assertTrue(withoutPreferred == autoBaseline)
    }

    @Test
    fun `MALE gender with a preferred language adds a gender clause mentioning male`() {
        val prompt = TranslationPrompts.systemPromptFor(polish, UserGender.MALE, Languages.byCode("en"))
        assertTrue(hasGenderClause(prompt))
        assertTrue(prompt.contains("as male"))
    }

    @Test
    fun `FEMALE gender with a preferred language adds a gender clause mentioning female`() {
        val prompt = TranslationPrompts.systemPromptFor(polish, UserGender.FEMALE, Languages.byCode("en"))
        assertTrue(hasGenderClause(prompt))
        assertTrue(prompt.contains("as female"))
    }

    @Test
    fun `gender clause names both the user's preferred language and the translation target`() {
        val preferred = Languages.byCode("pl")!! // Polish
        val prompt = TranslationPrompts.systemPromptFor(english, UserGender.MALE, preferred) // target: English
        assertTrue(prompt.contains("preferred/primary language is Polish"))
        assertTrue(prompt.contains("translation target (English) is a different language from Polish"))
    }

    @Test
    fun `gender clause requires BOTH source-matches-preferred AND target-differs-from-preferred`() {
        val prompt = TranslationPrompts.systemPromptFor(english, UserGender.MALE, Languages.byCode("pl"))
        assertTrue(prompt.contains("ONLY when both of the following"))
        assertTrue(prompt.contains("the detected source language is Polish"))
    }

    @Test
    fun `gender clause explicitly excludes text translated INTO the user's own preferred language`() {
        // "I was tired yesterday ?pl" with preferred language Polish: the
        // translation target IS the preferred language, so the declared
        // gender must not be applied — the source could be someone else's
        // message pasted in for translation.
        val prompt = TranslationPrompts.systemPromptFor(polish, UserGender.MALE, Languages.byCode("pl"))
        assertTrue(prompt.contains("do NOT apply the declared gender at all"))
        assertTrue(prompt.contains("may have been written by someone else"))
    }

    @Test
    fun `gender clause scopes application to first-person forms only`() {
        val prompt = TranslationPrompts.systemPromptFor(english, UserGender.FEMALE, Languages.byCode("pl"))
        assertTrue(prompt.contains("grammatical forms that refer directly to"))
        assertTrue(prompt.contains("the speaker/author"))
    }

    @Test
    fun `gender clause explicitly forbids applying it to anyone else in the text`() {
        val prompt = TranslationPrompts.systemPromptFor(polish, UserGender.MALE, Languages.byCode("en"))
        assertTrue(prompt.contains("never use the declared gender to infer, assume, or change the gender of anyone"))
        assertTrue(prompt.contains("every other person's gender"))
    }

    @Test
    fun `gender clause forbids guessing when the source gives no gender information`() {
        val prompt = TranslationPrompts.systemPromptFor(polish, UserGender.FEMALE, Languages.byCode("en"))
        assertTrue(prompt.contains("do not guess — preserve the ambiguity"))
    }

    @Test
    fun `base prompt instructs preserving singular-plural, person, and tense`() {
        val prompt = TranslationPrompts.systemPromptFor(english)
        assertTrue(prompt.contains("grammatical number"))
        assertTrue(prompt.contains("singular vs. plural") || prompt.contains("singular"))
        assertTrue(prompt.contains("grammatical person"))
        assertTrue(prompt.contains("tense"))
    }

    @Test
    fun `base prompt forbids inventing context and forbids flipping singular-plural`() {
        val prompt = TranslationPrompts.systemPromptFor(english)
        assertTrue(prompt.contains("Never invent information"))
        assertTrue(prompt.contains("Never change singular to plural or plural to singular"))
    }

    @Test
    fun `base prompt preserves ambiguity rather than resolving it`() {
        val prompt = TranslationPrompts.systemPromptFor(english)
        assertTrue(prompt.contains("If the source is ambiguous"))
        assertTrue(prompt.contains("preserve it"))
    }

    @Test
    fun `base prompt protects proper nouns, mentions, URLs, numbers, emoji, and formatting`() {
        val prompt = TranslationPrompts.systemPromptFor(english)
        assertTrue(prompt.contains("proper nouns"))
        assertTrue(prompt.contains("@mentions"))
        assertTrue(prompt.contains("URLs"))
        assertTrue(prompt.contains("numbers"))
        assertTrue(prompt.contains("emoji"))
        assertTrue(prompt.contains("formatting"))
    }

    @Test
    fun `base prompt handles text already in the target language as a grammar-only pass`() {
        val prompt = TranslationPrompts.systemPromptFor(english)
        assertTrue(prompt.contains("already written in"))
        assertTrue(prompt.contains("Only correct grammar"))
    }

    @Test
    fun `base prompt forbids commentary, labels, quotes, and alternative versions in the response`() {
        val prompt = TranslationPrompts.systemPromptFor(english)
        assertTrue(prompt.contains("Do not include quotation marks"))
        assertTrue(prompt.contains("alternative versions"))
    }

    @Test
    fun `EN and PL convenience constants carry no speaker-declared-gender clause`() {
        assertFalse(hasGenderClause(TranslationPrompts.EN_TRANSLATION_SYSTEM_PROMPT))
        assertFalse(hasGenderClause(TranslationPrompts.PL_TRANSLATION_SYSTEM_PROMPT))
    }

    @Test
    fun `target language name is embedded in English regardless of target`() {
        val prompt = TranslationPrompts.systemPromptFor(TriggerDetector.Target("ja"))
        assertTrue(prompt.contains("Japanese"))
    }
}
