package com.textgate.ai.model

/**
 * The app owner's own grammatical gender, as a translation preference —
 * added in v1.7.0 alongside the "Translate my own message" system-prompt
 * rewrite (see [TranslationPrompts]). This is used for exactly ONE thing:
 * telling Gemini how to inflect first-person ("I"/"me"/"my") forms when
 * translating text the user themselves typed via a `?xx` trigger, in
 * languages that grammatically mark the speaker's own gender (e.g. past-
 * tense/perfective verb agreement in Polish, adjectives referring back to
 * the speaker in French or Spanish).
 *
 * [AUTO] ("Automatyczna/nieokreślona") is the default and means: apply no
 * fixed preference at all — Gemini either infers a natural neutral/default
 * form or preserves whatever the source text itself already implies. This
 * is NOT the same as "no answer yet"; it is an explicit, intentional user
 * choice not to declare a gender.
 *
 * This value is read ONLY by the typed-trigger translation path
 * ([com.textgate.ai.accessibility.TextGateAccessibilityService.confirmAndProcess]).
 * The long-press "translate a received message" bubble path
 * ([com.textgate.ai.accessibility.TextGateAccessibilityService.startBubbleTranslation])
 * MUST NOT read this setting — the text being translated there was written
 * by someone else, so the phone owner's own gender has no bearing on it and
 * must never be passed into that prompt. See each call site's own comment.
 */
enum class UserGender(val prefValue: String) {
    AUTO("auto"),
    MALE("male"),
    FEMALE("female");

    companion object {
        /** Falls back to [AUTO] for a missing or unrecognized stored value
         * (e.g. a future release that ever removed an option), never
         * throws — same defensive pattern as
         * [com.textgate.ai.model.Languages.byCode] elsewhere in this app. */
        fun fromPrefValue(value: String?): UserGender =
            entries.firstOrNull { it.prefValue == value } ?: AUTO
    }
}
