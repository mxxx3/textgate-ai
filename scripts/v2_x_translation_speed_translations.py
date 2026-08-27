# -*- coding: utf-8 -*-
# Real, per-language translations for the 7 new string keys added by the
# translation-voice-speed follow-up (AudioTrack.PlaybackParams, pitch
# always 1.0, speed only):
#   - section_translation_speed ("Translation voice speed" card title)
#   - translation_speed_description (explains this affects ONLY the
#     translated audio played back in Na żywo — never the mic, sample
#     rate, transcription, VAD, or routing)
#   - label_translation_speed_1_0 / _1_15 / _1_25 / _1_35 / _1_5 (the five
#     Spinner options — see TranslationPlaybackSpeed)
#
# Same reasoning and convention as v2_0_1_audio_capture_translations.py /
# v2_x_error_routing_translations.py: this project's lint config
# (`abortOnError = true`) treats MissingTranslation as a build-breaking
# Error, not a warning, so every new key must be translated into all 36
# non-English/non-Polish locales, not just left to Android's default-locale
# runtime fallback. The numeral+"x" labels are locale-invariant by nature
# (e.g. "1.25x") — only the short parenthetical word ("default", "normal",
# "fastest") is real per-language text.
#
# Values are plain (unescaped) text. inject_v2_x_translation_speed_translations.py
# performs the Android string-resource XML escaping (&, <, >, leading @/?,
# apostrophes, quotes) when writing these into each values-XX/strings.xml.
# "Gemini" and "Na żywo" (this app's own screen/feature name) are
# deliberately left untranslated/unlocalized, matching how existing keys in
# this project already treat these as proper nouns.

TRANSLATIONS = {}

TRANSLATIONS["ar"] = {
    "section_translation_speed": "سرعة صوت الترجمة",
    "translation_speed_description": "مدى سرعة تشغيل صوت Gemini المترجم في Na żywo. تسريعه لا يغيّر حدة الصوت — يؤثر فقط على الصوت المترجم الذي تسمعه، ولا يؤثر أبدًا على ما يرسله الميكروفون إلى Gemini.",
    "label_translation_speed_1_0": "1.0x (عادي)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (افتراضي)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (الأسرع)",
}

TRANSLATIONS["bg"] = {
    "section_translation_speed": "Скорост на гласа за превод",
    "translation_speed_description": "Колко бързо се възпроизвежда преведеният глас на Gemini в Na żywo. Ускоряването не променя височината на тона — засяга само преведения звук, който чувате, никога това, което микрофонът изпраща до Gemini.",
    "label_translation_speed_1_0": "1.0x (нормална)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (по подразбиране)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (най-бърза)",
}

TRANSLATIONS["ca"] = {
    "section_translation_speed": "Velocitat de la veu de traducció",
    "translation_speed_description": "A quina velocitat es reprodueix la veu traduïda de Gemini a Na żywo. Accelerar-la no canvia el to — només afecta l'àudio traduït que sents, mai el que el micròfon envia a Gemini.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (per defecte)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (la més ràpida)",
}

TRANSLATIONS["cs"] = {
    "section_translation_speed": "Rychlost hlasu překladu",
    "translation_speed_description": "Jak rychle se přehrává přeložený hlas Gemini v Na żywo. Zrychlení nemění výšku hlasu — týká se pouze přeloženého zvuku, který slyšíte, nikdy toho, co mikrofon odesílá do Gemini.",
    "label_translation_speed_1_0": "1.0x (normální)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (výchozí)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (nejrychlejší)",
}

TRANSLATIONS["da"] = {
    "section_translation_speed": "Oversættelsesstemmens hastighed",
    "translation_speed_description": "Hvor hurtigt Geminis oversatte stemme afspilles i Na żywo. Hurtigere afspilning ændrer ikke tonehøjden — det påvirker kun den oversatte lyd, du hører, aldrig det, mikrofonen sender til Gemini.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (standard)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (hurtigst)",
}

TRANSLATIONS["de"] = {
    "section_translation_speed": "Geschwindigkeit der Übersetzungsstimme",
    "translation_speed_description": "Wie schnell Geminis übersetzte Stimme in Na żywo abgespielt wird. Eine höhere Geschwindigkeit ändert nicht die Tonhöhe — betrifft nur das übersetzte Audio, das du hörst, nie das, was das Mikrofon an Gemini sendet.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (Standard)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (am schnellsten)",
}

TRANSLATIONS["el"] = {
    "section_translation_speed": "Ταχύτητα φωνής μετάφρασης",
    "translation_speed_description": "Πόσο γρήγορα αναπαράγεται η μεταφρασμένη φωνή του Gemini στο Na żywo. Η επιτάχυνση δεν αλλάζει τον τόνο — επηρεάζει μόνο τον μεταφρασμένο ήχο που ακούτε, ποτέ αυτό που στέλνει το μικρόφωνο στο Gemini.",
    "label_translation_speed_1_0": "1.0x (κανονική)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (προεπιλογή)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (η ταχύτερη)",
}

TRANSLATIONS["es"] = {
    "section_translation_speed": "Velocidad de la voz de traducción",
    "translation_speed_description": "A qué velocidad se reproduce la voz traducida de Gemini en Na żywo. Acelerarla no cambia el tono — solo afecta al audio traducido que escuchas, nunca a lo que el micrófono envía a Gemini.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (predeterminada)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (la más rápida)",
}

TRANSLATIONS["et"] = {
    "section_translation_speed": "Tõlke hääle kiirus",
    "translation_speed_description": "Kui kiiresti mängitakse Gemini tõlgitud häält funktsioonis Na żywo. Kiirendamine ei muuda hääle kõrgust — see mõjutab ainult tõlgitud heli, mida kuuled, mitte kunagi seda, mida mikrofon Geminile saadab.",
    "label_translation_speed_1_0": "1.0x (tavaline)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (vaikimisi)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (kiireim)",
}

TRANSLATIONS["fa"] = {
    "section_translation_speed": "سرعت صدای ترجمه",
    "translation_speed_description": "صدای ترجمه‌شده Gemini در Na żywo با چه سرعتی پخش می‌شود. افزایش سرعت زیر و بمی صدا را تغییر نمی‌دهد — فقط روی صدای ترجمه‌شده‌ای که می‌شنوید تأثیر می‌گذارد، هرگز روی چیزی که میکروفون به Gemini می‌فرستد.",
    "label_translation_speed_1_0": "1.0x (عادی)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (پیش‌فرض)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (سریع‌ترین)",
}

TRANSLATIONS["fi"] = {
    "section_translation_speed": "Käännösäänen nopeus",
    "translation_speed_description": "Kuinka nopeasti Geminin käännetty ääni toistetaan Na żywo -tilassa. Nopeuttaminen ei muuta äänenkorkeutta — se vaikuttaa vain kuulemaasi käännettyyn ääneen, ei koskaan siihen, mitä mikrofoni lähettää Geminille.",
    "label_translation_speed_1_0": "1.0x (normaali)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (oletus)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (nopein)",
}

TRANSLATIONS["fr"] = {
    "section_translation_speed": "Vitesse de la voix de traduction",
    "translation_speed_description": "À quelle vitesse la voix traduite de Gemini est lue dans Na żywo. Accélérer ne change pas la hauteur de la voix — cela n'affecte que l'audio traduit que vous entendez, jamais ce que le microphone envoie à Gemini.",
    "label_translation_speed_1_0": "1.0x (normale)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (par défaut)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (la plus rapide)",
}

TRANSLATIONS["hi"] = {
    "section_translation_speed": "अनुवाद आवाज़ की गति",
    "translation_speed_description": "Na żywo में Gemini की अनुवादित आवाज़ कितनी तेज़ी से चलती है। तेज़ करने से आवाज़ की पिच नहीं बदलती — यह केवल आपके द्वारा सुनी जाने वाली अनुवादित ऑडियो को प्रभावित करता है, कभी भी माइक्रोफ़ोन द्वारा Gemini को भेजी जाने वाली ऑडियो को नहीं।",
    "label_translation_speed_1_0": "1.0x (सामान्य)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (डिफ़ॉल्ट)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (सबसे तेज़)",
}

TRANSLATIONS["hr"] = {
    "section_translation_speed": "Brzina glasa prijevoda",
    "translation_speed_description": "Koliko se brzo reproducira Geminijev prevedeni glas u Na żywo. Ubrzavanje ne mijenja visinu tona — utječe samo na prevedeni zvuk koji čujete, nikada na ono što mikrofon šalje Geminiju.",
    "label_translation_speed_1_0": "1.0x (normalna)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (zadano)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (najbrža)",
}

TRANSLATIONS["hu"] = {
    "section_translation_speed": "Fordítási hang sebessége",
    "translation_speed_description": "Milyen gyorsan játssza le a Gemini lefordított hangját a Na żywo. A gyorsítás nem változtatja meg a hangmagasságot — csak a hallott lefordított hangra hat, soha nem arra, amit a mikrofon küld a Geminihez.",
    "label_translation_speed_1_0": "1.0x (normál)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (alapértelmezett)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (leggyorsabb)",
}

TRANSLATIONS["in"] = {
    "section_translation_speed": "Kecepatan suara terjemahan",
    "translation_speed_description": "Seberapa cepat suara terjemahan Gemini diputar di Na żywo. Mempercepat tidak mengubah nada suara — ini hanya memengaruhi audio terjemahan yang Anda dengar, tidak pernah apa yang dikirim mikrofon ke Gemini.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (default)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (tercepat)",
}

TRANSLATIONS["it"] = {
    "section_translation_speed": "Velocità della voce di traduzione",
    "translation_speed_description": "Quanto velocemente viene riprodotta la voce tradotta di Gemini in Na żywo. Accelerarla non cambia il tono — influisce solo sull'audio tradotto che senti, mai su ciò che il microfono invia a Gemini.",
    "label_translation_speed_1_0": "1.0x (normale)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (predefinita)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (la più veloce)",
}

TRANSLATIONS["iw"] = {
    "section_translation_speed": "מהירות קול התרגום",
    "translation_speed_description": "באיזו מהירות מושמע קולו המתורגם של Gemini ב-Na żywo. האצה אינה משנה את גובה הקול — היא משפיעה רק על השמע המתורגם שאתה שומע, לעולם לא על מה שהמיקרופון שולח ל-Gemini.",
    "label_translation_speed_1_0": "1.0x (רגילה)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (ברירת מחדל)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (המהירה ביותר)",
}

TRANSLATIONS["ja"] = {
    "section_translation_speed": "翻訳音声の速度",
    "translation_speed_description": "Na żywo で Gemini の翻訳音声を再生する速さです。速くしてもピッチ（音の高さ）は変わりません — 影響するのは聞こえる翻訳音声だけで、マイクが Gemini に送信する音声には影響しません。",
    "label_translation_speed_1_0": "1.0x（通常）",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x（デフォルト）",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x（最速）",
}

TRANSLATIONS["ko"] = {
    "section_translation_speed": "번역 음성 속도",
    "translation_speed_description": "Na żywo에서 Gemini의 번역된 음성이 재생되는 속도입니다. 속도를 높여도 음높이는 변하지 않습니다 — 듣는 번역 오디오에만 영향을 주며, 마이크가 Gemini로 보내는 오디오에는 절대 영향을 주지 않습니다.",
    "label_translation_speed_1_0": "1.0x (보통)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (기본값)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (가장 빠름)",
}

TRANSLATIONS["lt"] = {
    "section_translation_speed": "Vertimo balso greitis",
    "translation_speed_description": "Kaip greitai Na żywo atkuriamas Gemini išverstas balsas. Pagreitinimas nekeičia balso aukščio — tai veikia tik girdimą išverstą garsą, niekada to, ką mikrofonas siunčia Gemini.",
    "label_translation_speed_1_0": "1.0x (normalus)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (numatytasis)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (greičiausias)",
}

TRANSLATIONS["lv"] = {
    "section_translation_speed": "Tulkojuma balss ātrums",
    "translation_speed_description": "Cik ātri Na żywo tiek atskaņota Gemini tulkotā balss. Paātrināšana nemaina balss augstumu — tas ietekmē tikai dzirdēto tulkoto audio, nekad to, ko mikrofons sūta Gemini.",
    "label_translation_speed_1_0": "1.0x (normāls)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (noklusējuma)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (ātrākais)",
}

TRANSLATIONS["ms"] = {
    "section_translation_speed": "Kelajuan suara terjemahan",
    "translation_speed_description": "Sepantas mana suara terjemahan Gemini dimainkan dalam Na żywo. Mempercepatkannya tidak mengubah nada suara — ini hanya memberi kesan kepada audio terjemahan yang anda dengar, tidak sekali-kali apa yang mikrofon hantar kepada Gemini.",
    "label_translation_speed_1_0": "1.0x (biasa)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (lalai)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (terpantas)",
}

TRANSLATIONS["nb"] = {
    "section_translation_speed": "Hastighet på oversettelsesstemmen",
    "translation_speed_description": "Hvor raskt Geminis oversatte stemme spilles av i Na żywo. Å øke hastigheten endrer ikke tonehøyden — det påvirker bare den oversatte lyden du hører, aldri det mikrofonen sender til Gemini.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (standard)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (raskest)",
}

TRANSLATIONS["nl"] = {
    "section_translation_speed": "Snelheid van de vertaalstem",
    "translation_speed_description": "Hoe snel Gemini's vertaalde stem wordt afgespeeld in Na żywo. Sneller afspelen verandert de toonhoogte niet — dit heeft alleen invloed op de vertaalde audio die je hoort, nooit op wat de microfoon naar Gemini stuurt.",
    "label_translation_speed_1_0": "1.0x (normaal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (standaard)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (snelst)",
}

TRANSLATIONS["pt"] = {
    "section_translation_speed": "Velocidade da voz de tradução",
    "translation_speed_description": "A que velocidade a voz traduzida do Gemini é reproduzida no Na żywo. Acelerar não altera o tom de voz — afeta apenas o áudio traduzido que você ouve, nunca o que o microfone envia ao Gemini.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (padrão)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (mais rápida)",
}

TRANSLATIONS["ro"] = {
    "section_translation_speed": "Viteza vocii de traducere",
    "translation_speed_description": "Cât de repede este redată vocea tradusă de Gemini în Na żywo. Accelerarea nu schimbă înălțimea vocii — afectează doar audio-ul tradus pe care îl auzi, niciodată ceea ce microfonul trimite către Gemini.",
    "label_translation_speed_1_0": "1.0x (normală)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (implicită)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (cea mai rapidă)",
}

TRANSLATIONS["ru"] = {
    "section_translation_speed": "Скорость голоса перевода",
    "translation_speed_description": "Как быстро воспроизводится переведённый голос Gemini в Na żywo. Ускорение не меняет высоту голоса — влияет только на переведённый звук, который вы слышите, но никогда на то, что микрофон отправляет в Gemini.",
    "label_translation_speed_1_0": "1.0x (обычная)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (по умолчанию)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (самая быстрая)",
}

TRANSLATIONS["sk"] = {
    "section_translation_speed": "Rýchlosť hlasu prekladu",
    "translation_speed_description": "Ako rýchlo sa prehráva preložený hlas Gemini v Na żywo. Zrýchlenie nemení výšku hlasu — týka sa iba preloženého zvuku, ktorý počujete, nikdy toho, čo mikrofón odosiela do Gemini.",
    "label_translation_speed_1_0": "1.0x (normálna)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (predvolená)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (najrýchlejšia)",
}

TRANSLATIONS["sl"] = {
    "section_translation_speed": "Hitrost glasu prevoda",
    "translation_speed_description": "Kako hitro se v Na żywo predvaja Geminijev preveden glas. Pospešitev ne spremeni višine glasu — vpliva samo na preveden zvok, ki ga slišite, nikoli na to, kar mikrofon pošlje Geminiju.",
    "label_translation_speed_1_0": "1.0x (običajna)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (privzeta)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (najhitrejša)",
}

TRANSLATIONS["sr"] = {
    "section_translation_speed": "Брзина гласа превода",
    "translation_speed_description": "Колико брзо се пушта Gemini-јев преведени глас у Na żywo. Убрзавање не мења висину гласа — утиче само на преведени звук који чујете, никада на оно што микрофон шаље ка Gemini-ју.",
    "label_translation_speed_1_0": "1.0x (нормална)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (подразумевана)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (најбржа)",
}

TRANSLATIONS["th"] = {
    "section_translation_speed": "ความเร็วเสียงคำแปล",
    "translation_speed_description": "เสียงแปลของ Gemini เล่นเร็วแค่ไหนใน Na żywo การเร่งความเร็วไม่เปลี่ยนระดับเสียงสูงต่ำ — มีผลเฉพาะกับเสียงที่แปลแล้วที่คุณได้ยินเท่านั้น ไม่มีผลต่อสิ่งที่ไมโครโฟนส่งไปยัง Gemini",
    "label_translation_speed_1_0": "1.0x (ปกติ)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (ค่าเริ่มต้น)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (เร็วที่สุด)",
}

TRANSLATIONS["tr"] = {
    "section_translation_speed": "Çeviri sesi hızı",
    "translation_speed_description": "Na żywo'da Gemini'nin çevrilmiş sesinin ne kadar hızlı çalındığı. Hızlandırmak perdeyi değiştirmez — yalnızca duyduğunuz çevrilmiş sesi etkiler, mikrofonun Gemini'ye gönderdiği sesi asla etkilemez.",
    "label_translation_speed_1_0": "1.0x (normal)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (varsayılan)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (en hızlı)",
}

TRANSLATIONS["uk"] = {
    "section_translation_speed": "Швидкість голосу перекладу",
    "translation_speed_description": "Наскільки швидко відтворюється перекладений голос Gemini у Na żywo. Прискорення не змінює висоту голосу — впливає лише на перекладений звук, який ви чуєте, і ніколи на те, що мікрофон надсилає до Gemini.",
    "label_translation_speed_1_0": "1.0x (звичайна)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (типова)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (найшвидша)",
}

TRANSLATIONS["vi"] = {
    "section_translation_speed": "Tốc độ giọng dịch",
    "translation_speed_description": "Giọng dịch của Gemini được phát nhanh đến mức nào trong Na żywo. Tăng tốc không thay đổi cao độ giọng nói — chỉ ảnh hưởng đến âm thanh đã dịch mà bạn nghe, không bao giờ ảnh hưởng đến âm thanh micro gửi đến Gemini.",
    "label_translation_speed_1_0": "1.0x (bình thường)",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x (mặc định)",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x (nhanh nhất)",
}

TRANSLATIONS["zh"] = {
    "section_translation_speed": "翻译语音速度",
    "translation_speed_description": "Gemini 翻译语音在 Na żywo 中的播放速度。加快速度不会改变音调 — 只影响你听到的翻译音频，绝不影响麦克风发送给 Gemini 的内容。",
    "label_translation_speed_1_0": "1.0x（正常）",
    "label_translation_speed_1_15": "1.15x",
    "label_translation_speed_1_25": "1.25x（默认）",
    "label_translation_speed_1_35": "1.35x",
    "label_translation_speed_1_5": "1.5x（最快）",
}
