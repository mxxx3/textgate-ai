package com.textgate.ai.model

/**
 * One supported translation/interface language. This is the single source
 * of truth every other language-aware piece of this app builds from:
 *   - [code] is both the typed-trigger suffix (e.g. typing "?de" at the
 *     end of a message) AND the persisted value TextGate AI stores in
 *     Settings for "which language" (see AppSettingsStore). It is also
 *     used, unmodified, as the Android resource-qualifier suffix for this
 *     app's own interface strings (`values-<code>/strings.xml`) — which is
 *     why a few entries below use Android's own legacy qualifier spelling
 *     ("in" for Indonesian, "iw" for Hebrew, "nb" for Norwegian, "pt-rBR"
 *     / "zh-rCN" for the region-qualified variants) rather than the more
 *     modern ISO codes those languages are otherwise known by. Keeping
 *     exactly one code per language, reused for all three purposes, is a
 *     deliberate simplicity trade-off over maintaining parallel code
 *     systems that could silently drift out of sync.
 *   - [nativeName] is that language's own name, in its own script — this
 *     is what the in-app language pickers display, and it is always shown
 *     the same way regardless of which language the interface is
 *     currently in, exactly like every native OS language picker does
 *     (a Polish speaker and a Japanese speaker both see "日本語" for
 *     Japanese, not a translation of the word "Japanese").
 *   - [englishName] is the plain-English language name used inside the
 *     system prompt sent to Gemini (see TranslationPrompts) — kept
 *     separate from [nativeName] because an instruction to a translation
 *     model reads more reliably in one consistent language (English) than
 *     mixed-script prose would.
 *   - [localeLanguageTag] is the BCP-47 tag used to actually construct a
 *     [java.util.Locale] at runtime (see LocaleHelper) — this is where the
 *     handful of legacy-vs-modern code differences are resolved back to
 *     the modern tag the platform's Locale APIs expect (e.g. "id" instead
 *     of "in", "he" instead of "iw"). Defaults to [code] for every
 *     language where the two are identical.
 */
data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val localeLanguageTag: String = code
)

/**
 * The full list of languages TextGate AI supports, as of v1.4.0 — chosen
 * by the app's owner. Order here is the order every language picker in
 * the app shows them in (alphabetical by [code], which conveniently also
 * groups the language reasonably alphabetically by [englishName] for a
 * Latin-script reader, with a few exceptions — a real search/filter box
 * was judged unnecessary for a 40-entry list shown in a scrollable
 * picker).
 */
object Languages {

    val ALL: List<SupportedLanguage> = listOf(
        SupportedLanguage("en", "English", "English"),
        SupportedLanguage("ar", "العربية", "Arabic"),
        SupportedLanguage("bg", "Български", "Bulgarian"),
        SupportedLanguage("ca", "Català", "Catalan"),
        SupportedLanguage("cs", "Čeština", "Czech"),
        SupportedLanguage("da", "Dansk", "Danish"),
        SupportedLanguage("de", "Deutsch", "German"),
        SupportedLanguage("el", "Ελληνικά", "Greek"),
        SupportedLanguage("es", "Español", "Spanish"),
        SupportedLanguage("et", "Eesti", "Estonian"),
        SupportedLanguage("fa", "فارسی", "Persian"),
        SupportedLanguage("fi", "Suomi", "Finnish"),
        SupportedLanguage("fr", "Français", "French"),
        SupportedLanguage("hi", "हिन्दी", "Hindi"),
        SupportedLanguage("hr", "Hrvatski", "Croatian"),
        SupportedLanguage("hu", "Magyar", "Hungarian"),
        SupportedLanguage("in", "Bahasa Indonesia", "Indonesian", localeLanguageTag = "id"),
        SupportedLanguage("it", "Italiano", "Italian"),
        SupportedLanguage("iw", "עברית", "Hebrew", localeLanguageTag = "he"),
        SupportedLanguage("ja", "日本語", "Japanese"),
        SupportedLanguage("ko", "한국어", "Korean"),
        SupportedLanguage("lt", "Lietuvių", "Lithuanian"),
        SupportedLanguage("lv", "Latviešu", "Latvian"),
        SupportedLanguage("ms", "Bahasa Melayu", "Malay"),
        SupportedLanguage("nb", "Norsk bokmål", "Norwegian", localeLanguageTag = "nb"),
        SupportedLanguage("nl", "Nederlands", "Dutch"),
        SupportedLanguage("pl", "Polski", "Polish"),
        SupportedLanguage("pt", "Português", "Portuguese"),
        SupportedLanguage("pt-rBR", "Português (Brasil)", "Portuguese (Brazil)", localeLanguageTag = "pt-BR"),
        SupportedLanguage("ro", "Română", "Romanian"),
        SupportedLanguage("ru", "Русский", "Russian"),
        SupportedLanguage("sk", "Slovenčina", "Slovak"),
        SupportedLanguage("sl", "Slovenščina", "Slovenian"),
        SupportedLanguage("sr", "Српски", "Serbian"),
        SupportedLanguage("th", "ไทย", "Thai"),
        SupportedLanguage("tr", "Türkçe", "Turkish"),
        SupportedLanguage("uk", "Українська", "Ukrainian"),
        SupportedLanguage("vi", "Tiếng Việt", "Vietnamese"),
        SupportedLanguage("zh", "中文", "Chinese"),
        SupportedLanguage("zh-rCN", "中文（简体）", "Chinese (Simplified)", localeLanguageTag = "zh-CN")
    )

    private val BY_CODE: Map<String, SupportedLanguage> = ALL.associateBy { it.code }

    /** Case-insensitive lookup by [SupportedLanguage.code] — trigger
     * matching in [com.textgate.ai.security.TriggerDetector] is itself
     * case-insensitive, so this stays consistent with that. */
    fun byCode(code: String): SupportedLanguage? = BY_CODE[code] ?: BY_CODE[code.lowercase()]

    /** The language TextGate AI falls back to whenever a stored code is
     * missing or no longer recognized (e.g. after a downgrade). Polish,
     * matching this app's original, pre-multi-language default. */
    val DEFAULT: SupportedLanguage = BY_CODE.getValue("pl")
}
