# -*- coding: utf-8 -*-
# Real, per-language translations for the 4 new v2.0.1 string keys added for
# the audio-capture-mode setting (Settings > Audio i Live). Same reasoning
# and convention as v2_translations.py: this project's lint config
# (`abortOnError = true`) treats MissingTranslation as a build-breaking
# Error, not a warning, so every new key must be translated into all 38
# non-English/non-Polish locales, not just left to Android's default-locale
# runtime fallback.
#
# Values are plain (unescaped) text. inject_v2_0_1_audio_capture_translations.py
# performs the Android string-resource XML escaping (&, <, >, leading @/?,
# apostrophes, quotes) when writing these into each values-XX/strings.xml.

TRANSLATIONS = {}

TRANSLATIONS["ar"] = {
    "section_audio_capture_mode": "وضع التقاط الصوت",
    "audio_capture_mode_description": "كيف تلتقط ميزتا \"محادثة\" و\"مباشر\" الصوت وتشغلانه. يمنع وضع \"إلغاء الصدى\" الميكروفون من التقاط كلام الهاتف المترجم نفسه من مكبر الصوت، لكنه يضيف معالجة وقد يسبب تأخيرًا أو انقطاع الأدوار على بعض الهواتف. وضع \"قياسي\" أسرع وأبسط، لكن استخدمه فقط مع سماعات الرأس — فعلى مكبر الصوت قد يلتقط مخرجاته الخاصة ويترجمها في حلقة متكررة.",
    "label_audio_capture_echo_cancelled": "إلغاء الصدى (موصى به)",
    "label_audio_capture_standard": "قياسي (زمن استجابة أقل)",
}

TRANSLATIONS["bg"] = {
    "section_audio_capture_mode": "Режим на записване на звук",
    "audio_capture_mode_description": "Как „Разговор“ и „На живо“ записват и възпроизвеждат звук. „Без ехо“ пречи на микрофона да чуе собствената преведена реч на телефона от високоговорителя, но добавя обработка и може да причини забавяне или прекъснати реплики на някои телефони. „Стандартен“ е по-бърз и по-прост, но го използвайте само със слушалки — на високоговорител може да улови собствения си изход и да го преведе отново в цикъл.",
    "label_audio_capture_echo_cancelled": "Без ехо (препоръчително)",
    "label_audio_capture_standard": "Стандартен (по-ниско закъснение)",
}

TRANSLATIONS["ca"] = {
    "section_audio_capture_mode": "Mode de captura d'àudio",
    "audio_capture_mode_description": "Com Conversa i En directe capturen i reprodueixen àudio. \"Sense eco\" evita que el micròfon senti la pròpia veu traduïda del telèfon a l'altaveu, però afegeix processament i pot causar retard o talls en alguns telèfons. \"Estàndard\" és més ràpid i senzill, però useu-lo només amb auriculars — a l'altaveu pot captar la seva pròpia sortida i tornar-la a traduir en bucle.",
    "label_audio_capture_echo_cancelled": "Sense eco (recomanat)",
    "label_audio_capture_standard": "Estàndard (menys latència)",
}

TRANSLATIONS["cs"] = {
    "section_audio_capture_mode": "Režim snímání zvuku",
    "audio_capture_mode_description": "Jak Rozhovor a Naživo zaznamenávají a přehrávají zvuk. „Potlačení ozvěny“ brání mikrofonu slyšet vlastní přeloženou řeč telefonu z reproduktoru, ale přidává zpracování a na některých telefonech může způsobit zpoždění nebo přerušené repliky. „Standardní“ je rychlejší a jednodušší, ale používejte jej pouze se sluchátky — na reproduktoru může zachytit vlastní výstup a znovu jej přeložit ve smyčce.",
    "label_audio_capture_echo_cancelled": "Potlačení ozvěny (doporučeno)",
    "label_audio_capture_standard": "Standardní (nižší latence)",
}

TRANSLATIONS["da"] = {
    "section_audio_capture_mode": "Lydoptagelsestilstand",
    "audio_capture_mode_description": "Hvordan Samtale og Live optager og afspiller lyd. \"Ekkoannullering\" forhindrer mikrofonen i at høre telefonens egen oversatte tale i højttaleren, men tilføjer behandling og kan give forsinkelse eller afbrudte replikker på nogle telefoner. \"Standard\" er hurtigere og enklere, men brug den kun med hovedtelefoner — i højttaleren kan den opfange sin egen lyd og oversætte den igen i en løkke.",
    "label_audio_capture_echo_cancelled": "Ekkoannullering (anbefalet)",
    "label_audio_capture_standard": "Standard (lavere forsinkelse)",
}

TRANSLATIONS["de"] = {
    "section_audio_capture_mode": "Audioaufnahmemodus",
    "audio_capture_mode_description": "Wie Gespräch und Live Audio aufnehmen und wiedergeben. \"Echounterdrückung\" verhindert, dass das Mikrofon die eigene übersetzte Sprachausgabe des Telefons über den Lautsprecher hört, fügt aber Verarbeitung hinzu und kann bei manchen Telefonen zu Verzögerungen oder abgebrochenen Wortmeldungen führen. \"Standard\" ist schneller und einfacher, sollte aber nur mit Kopfhörern verwendet werden — über den Lautsprecher kann es die eigene Ausgabe aufnehmen und in einer Schleife erneut übersetzen.",
    "label_audio_capture_echo_cancelled": "Echounterdrückung (empfohlen)",
    "label_audio_capture_standard": "Standard (geringere Verzögerung)",
}

TRANSLATIONS["el"] = {
    "section_audio_capture_mode": "Λειτουργία λήψης ήχου",
    "audio_capture_mode_description": "Πώς οι λειτουργίες Συνομιλία και Ζωντανά καταγράφουν και αναπαράγουν ήχο. Η \"Απόρριψη ηχούς\" εμποδίζει το μικρόφωνο να ακούει τη μεταφρασμένη ομιλία του ίδιου του τηλεφώνου από το ηχείο, αλλά προσθέτει επεξεργασία και μπορεί να προκαλέσει καθυστέρηση ή διακοπές σε ορισμένα τηλέφωνα. Η \"Τυπική\" είναι πιο γρήγορη και απλή, αλλά χρησιμοποιήστε την μόνο με ακουστικά — στο ηχείο μπορεί να καταγράψει τη δική της έξοδο και να τη μεταφράσει ξανά σε βρόχο.",
    "label_audio_capture_echo_cancelled": "Απόρριψη ηχούς (προτείνεται)",
    "label_audio_capture_standard": "Τυπική (χαμηλότερη καθυστέρηση)",
}

TRANSLATIONS["es"] = {
    "section_audio_capture_mode": "Modo de captura de audio",
    "audio_capture_mode_description": "Cómo Conversación y En directo capturan y reproducen audio. \"Cancelación de eco\" evita que el micrófono escuche el propio habla traducida del teléfono por el altavoz, pero añade procesamiento y puede causar retraso o cortes en algunos teléfonos. \"Estándar\" es más rápido y sencillo, pero úsalo solo con auriculares — en el altavoz puede captar su propia salida y volver a traducirla en bucle.",
    "label_audio_capture_echo_cancelled": "Cancelación de eco (recomendado)",
    "label_audio_capture_standard": "Estándar (menor latencia)",
}

TRANSLATIONS["et"] = {
    "section_audio_capture_mode": "Heli salvestamise režiim",
    "audio_capture_mode_description": "Kuidas Vestlus ja Live heli salvestavad ja esitavad. \"Kaja summutus\" takistab mikrofonil telefoni enda tõlgitud kõnet valjuhääldist kuulmast, kuid lisab töötlust ning võib mõnel telefonil põhjustada viivitust või katkenud vooru. \"Standardne\" on kiirem ja lihtsam, kuid kasuta seda ainult kõrvaklappidega — valjuhääldis võib see püüda kinni oma väljundi ja tõlkida seda uuesti ringiga.",
    "label_audio_capture_echo_cancelled": "Kaja summutus (soovitatav)",
    "label_audio_capture_standard": "Standardne (väiksem viivitus)",
}

TRANSLATIONS["fa"] = {
    "section_audio_capture_mode": "حالت ضبط صدا",
    "audio_capture_mode_description": "نحوه ضبط و پخش صدا در مکالمه و زنده. «حذف پژواک» مانع از این می‌شود که میکروفون گفتار ترجمه‌شده خود گوشی را از بلندگو بشنود، اما پردازش بیشتری اضافه می‌کند و ممکن است در برخی گوشی‌ها باعث تأخیر یا قطع‌شدن نوبت‌ها شود. حالت «استاندارد» سریع‌تر و ساده‌تر است، اما فقط با هدفون استفاده کنید — روی بلندگو ممکن است خروجی خودش را دوباره بگیرد و آن را در یک حلقه دوباره ترجمه کند.",
    "label_audio_capture_echo_cancelled": "حذف پژواک (توصیه‌شده)",
    "label_audio_capture_standard": "استاندارد (تأخیر کمتر)",
}

TRANSLATIONS["fi"] = {
    "section_audio_capture_mode": "Äänen tallennustila",
    "audio_capture_mode_description": "Miten Keskustelu ja Live tallentavat ja toistavat ääntä. \"Kaiunpoisto\" estää mikrofonia kuulemasta puhelimen omaa käännettyä puhetta kaiuttimesta, mutta lisää käsittelyä ja voi aiheuttaa viivettä tai katkenneita vuoroja joissakin puhelimissa. \"Vakio\" on nopeampi ja yksinkertaisempi, mutta käytä sitä vain kuulokkeiden kanssa — kaiuttimessa se saattaa poimia oman äänensä ja kääntää sen uudelleen silmukassa.",
    "label_audio_capture_echo_cancelled": "Kaiunpoisto (suositeltu)",
    "label_audio_capture_standard": "Vakio (pienempi viive)",
}

TRANSLATIONS["fr"] = {
    "section_audio_capture_mode": "Mode de capture audio",
    "audio_capture_mode_description": "Comment Conversation et En direct capturent et lisent l'audio. « Suppression d'écho » empêche le micro d'entendre la propre voix traduite du téléphone via le haut-parleur, mais ajoute du traitement et peut provoquer un décalage ou des tours coupés sur certains téléphones. « Standard » est plus rapide et plus simple, mais à n'utiliser qu'avec un casque — au haut-parleur, il peut capter sa propre sortie et la retraduire en boucle.",
    "label_audio_capture_echo_cancelled": "Suppression d'écho (recommandé)",
    "label_audio_capture_standard": "Standard (latence réduite)",
}

TRANSLATIONS["hi"] = {
    "section_audio_capture_mode": "ऑडियो कैप्चर मोड",
    "audio_capture_mode_description": "बातचीत और लाइव ऑडियो को कैसे रिकॉर्ड और चलाते हैं। \"इको-कैंसिल्ड\" माइक्रोफ़ोन को स्पीकर पर फ़ोन की अपनी अनुवादित आवाज़ सुनने से रोकता है, लेकिन इससे प्रोसेसिंग बढ़ती है और कुछ फ़ोन पर देरी या बातचीत के बीच में कट सकती है। \"स्टैंडर्ड\" तेज़ और सरल है, लेकिन इसे केवल हेडफ़ोन के साथ उपयोग करें — स्पीकर पर यह अपनी ही आवाज़ पकड़कर उसे बार-बार अनुवाद कर सकता है।",
    "label_audio_capture_echo_cancelled": "इको-कैंसिल्ड (अनुशंसित)",
    "label_audio_capture_standard": "स्टैंडर्ड (कम विलंबता)",
}

TRANSLATIONS["hr"] = {
    "section_audio_capture_mode": "Način snimanja zvuka",
    "audio_capture_mode_description": "Kako Razgovor i Uživo snimaju i reproduciraju zvuk. \"Uklanjanje jeke\" sprječava mikrofon da čuje vlastiti prevedeni govor telefona s zvučnika, no dodaje obradu i na nekim telefonima može uzrokovati kašnjenje ili prekinute replike. \"Standardni\" je brži i jednostavniji, ali koristite ga samo sa slušalicama — na zvučniku može uhvatiti vlastiti izlaz i ponovno ga prevesti u petlji.",
    "label_audio_capture_echo_cancelled": "Uklanjanje jeke (preporučeno)",
    "label_audio_capture_standard": "Standardni (manja latencija)",
}

TRANSLATIONS["hu"] = {
    "section_audio_capture_mode": "Hangrögzítési mód",
    "audio_capture_mode_description": "Hogyan rögzíti és játssza le a hangot a Beszélgetés és az Élő mód. A \"Visszhangmentesítés\" megakadályozza, hogy a mikrofon meghallja a telefon saját lefordított beszédét a hangszóróból, de több feldolgozást igényel, és egyes telefonokon késést vagy megszakadó köröket okozhat. A \"Standard\" gyorsabb és egyszerűbb, de csak fülhallgatóval használja — hangszórón felveheti a saját kimenetét, és körkörösen újra lefordíthatja.",
    "label_audio_capture_echo_cancelled": "Visszhangmentesítés (ajánlott)",
    "label_audio_capture_standard": "Standard (alacsonyabb késleltetés)",
}

TRANSLATIONS["in"] = {
    "section_audio_capture_mode": "Mode perekaman audio",
    "audio_capture_mode_description": "Cara Percakapan dan Langsung merekam dan memutar audio. \"Peredam gema\" mencegah mikrofon mendengar suara terjemahan ponsel sendiri dari speaker, tetapi menambah pemrosesan dan bisa menyebabkan jeda atau giliran terputus di beberapa ponsel. \"Standar\" lebih cepat dan sederhana, tetapi gunakan hanya dengan headphone — pada speaker bisa menangkap keluarannya sendiri dan menerjemahkannya lagi berulang-ulang.",
    "label_audio_capture_echo_cancelled": "Peredam gema (disarankan)",
    "label_audio_capture_standard": "Standar (latensi lebih rendah)",
}

TRANSLATIONS["it"] = {
    "section_audio_capture_mode": "Modalità di acquisizione audio",
    "audio_capture_mode_description": "Come Conversazione e Dal vivo acquisiscono e riproducono l'audio. \"Cancellazione eco\" impedisce al microfono di sentire il parlato tradotto del telefono stesso dall'altoparlante, ma aggiunge elaborazione e può causare ritardi o interruzioni dei turni su alcuni telefoni. \"Standard\" è più veloce e semplice, ma usalo solo con le cuffie — sull'altoparlante potrebbe captare il proprio output e ritradurlo in un ciclo.",
    "label_audio_capture_echo_cancelled": "Cancellazione eco (consigliato)",
    "label_audio_capture_standard": "Standard (latenza inferiore)",
}

TRANSLATIONS["iw"] = {
    "section_audio_capture_mode": "מצב הקלטת שמע",
    "audio_capture_mode_description": "כיצד שיחה ובשידור חי מקליטים ומנגנים שמע. \"ביטול הד\" מונע מהמיקרופון לשמוע את הדיבור המתורגם של הטלפון עצמו מהרמקול, אך מוסיף עיבוד ועלול לגרום להשהיה או לתורות שנקטעות בטלפונים מסוימים. \"רגיל\" מהיר ופשוט יותר, אך יש להשתמש בו רק עם אוזניות — ברמקול הוא עלול לקלוט את הפלט של עצמו ולתרגם אותו שוב ושוב בלולאה.",
    "label_audio_capture_echo_cancelled": "ביטול הד (מומלץ)",
    "label_audio_capture_standard": "רגיל (השהיה נמוכה יותר)",
}

TRANSLATIONS["ja"] = {
    "section_audio_capture_mode": "音声キャプチャモード",
    "audio_capture_mode_description": "「会話」と「ライブ」が音声をどのように録音・再生するか。「エコーキャンセル」はマイクがスピーカーから流れる端末自身の翻訳音声を拾うのを防ぎますが、処理が増えるため一部の端末で遅延や会話の途切れが発生することがあります。「標準」はより高速でシンプルですが、ヘッドフォンと併用してください — スピーカーでは自分自身の出力を拾って繰り返し翻訳してしまうことがあります。",
    "label_audio_capture_echo_cancelled": "エコーキャンセル(推奨)",
    "label_audio_capture_standard": "標準(低遅延)",
}

TRANSLATIONS["ko"] = {
    "section_audio_capture_mode": "오디오 캡처 모드",
    "audio_capture_mode_description": "대화 및 라이브 기능이 오디오를 녹음하고 재생하는 방식입니다. \"에코 제거\"는 마이크가 스피커에서 나오는 휴대폰 자신의 번역된 음성을 듣지 못하게 하지만, 처리 과정이 추가되어 일부 휴대폰에서 지연이나 대화 끊김이 발생할 수 있습니다. \"표준\"은 더 빠르고 간단하지만 헤드폰과 함께만 사용하세요 — 스피커에서는 자신의 출력을 다시 감지해 반복적으로 번역할 수 있습니다.",
    "label_audio_capture_echo_cancelled": "에코 제거(권장)",
    "label_audio_capture_standard": "표준(지연 시간 감소)",
}

TRANSLATIONS["lt"] = {
    "section_audio_capture_mode": "Garso įrašymo režimas",
    "audio_capture_mode_description": "Kaip „Pokalbis“ ir „Tiesiogiai“ įrašo ir atkuria garsą. „Aido naikinimas“ neleidžia mikrofonui girdėti paties telefono išversto garso iš garsiakalbio, tačiau prideda apdorojimą ir kai kuriuose telefonuose gali sukelti vėlavimą ar nutrūkstančias eiles. „Standartinis“ yra greitesnis ir paprastesnis, tačiau naudokite jį tik su ausinėmis — per garsiakalbį jis gali užfiksuoti savo paties garsą ir vėl jį versti ratu.",
    "label_audio_capture_echo_cancelled": "Aido naikinimas (rekomenduojama)",
    "label_audio_capture_standard": "Standartinis (mažesnė delsa)",
}

TRANSLATIONS["lv"] = {
    "section_audio_capture_mode": "Audio uztveršanas režīms",
    "audio_capture_mode_description": "Kā \"Saruna\" un \"Tiešraide\" ieraksta un atskaņo audio. \"Atbalss slāpēšana\" neļauj mikrofonam dzirdēt paša tālruņa tulkoto runu no skaļruņa, taču pievieno apstrādi un dažos tālruņos var izraisīt aizkavi vai pārtrauktas kārtas. \"Standarta\" ir ātrāks un vienkāršāks, taču izmantojiet to tikai ar austiņām — skaļrunī tas var uztvert savu paša izvadi un atkal to tulkot ciklā.",
    "label_audio_capture_echo_cancelled": "Atbalss slāpēšana (ieteicams)",
    "label_audio_capture_standard": "Standarta (mazāka aizkave)",
}

TRANSLATIONS["ms"] = {
    "section_audio_capture_mode": "Mod tangkapan audio",
    "audio_capture_mode_description": "Cara Perbualan dan Langsung merakam dan memainkan audio. \"Penghapusan gema\" menghalang mikrofon daripada mendengar pertuturan terjemahan telefon itu sendiri daripada pembesar suara, tetapi menambah pemprosesan dan boleh menyebabkan lengah atau giliran terputus pada sesetengah telefon. \"Standard\" lebih pantas dan mudah, tetapi gunakan hanya dengan fon kepala — pada pembesar suara ia mungkin menangkap outputnya sendiri dan menterjemah semula secara berulang.",
    "label_audio_capture_echo_cancelled": "Penghapusan gema (disyorkan)",
    "label_audio_capture_standard": "Standard (kependaman lebih rendah)",
}

TRANSLATIONS["nb"] = {
    "section_audio_capture_mode": "Lydopptaksmodus",
    "audio_capture_mode_description": "Hvordan Samtale og Direkte tar opp og spiller av lyd. \"Ekkokansellering\" hindrer mikrofonen i å høre telefonens egen oversatte tale fra høyttaleren, men legger til prosessering og kan gi forsinkelse eller avbrutte replikker på enkelte telefoner. \"Standard\" er raskere og enklere, men bruk den kun med hodetelefoner — på høyttaleren kan den plukke opp sin egen lyd og oversette den på nytt i en løkke.",
    "label_audio_capture_echo_cancelled": "Ekkokansellering (anbefalt)",
    "label_audio_capture_standard": "Standard (lavere forsinkelse)",
}

TRANSLATIONS["nl"] = {
    "section_audio_capture_mode": "Audio-opnamemodus",
    "audio_capture_mode_description": "Hoe Gesprek en Live audio opnemen en afspelen. \"Echo-onderdrukking\" voorkomt dat de microfoon de eigen vertaalde spraak van de telefoon via de luidspreker hoort, maar voegt verwerking toe en kan op sommige telefoons vertraging of afgebroken beurten veroorzaken. \"Standaard\" is sneller en eenvoudiger, maar gebruik dit alleen met een koptelefoon — via de luidspreker kan het zijn eigen uitvoer oppikken en steeds opnieuw vertalen.",
    "label_audio_capture_echo_cancelled": "Echo-onderdrukking (aanbevolen)",
    "label_audio_capture_standard": "Standaard (lagere latentie)",
}

TRANSLATIONS["pt"] = {
    "section_audio_capture_mode": "Modo de captura de áudio",
    "audio_capture_mode_description": "Como Conversa e Ao vivo capturam e reproduzem áudio. \"Cancelamento de eco\" impede que o microfone ouça a própria fala traduzida do telefone pelo alto-falante, mas acrescenta processamento e pode causar atraso ou cortes em algumas conversas em certos telefones. \"Padrão\" é mais rápido e simples, mas use-o apenas com fones de ouvido — no alto-falante pode captar a própria saída e traduzi-la novamente em loop.",
    "label_audio_capture_echo_cancelled": "Cancelamento de eco (recomendado)",
    "label_audio_capture_standard": "Padrão (menor latência)",
}

TRANSLATIONS["ro"] = {
    "section_audio_capture_mode": "Mod de captare audio",
    "audio_capture_mode_description": "Cum înregistrează și redau sunet Conversație și În direct. „Anularea ecoului” împiedică microfonul să audă propriul discurs tradus al telefonului din difuzor, dar adaugă procesare și poate provoca întârzieri sau replici întrerupte pe unele telefoane. „Standard” este mai rapid și mai simplu, dar folosiți-l doar cu căști — pe difuzor poate capta propria ieșire și o poate retraduce într-o buclă.",
    "label_audio_capture_echo_cancelled": "Anularea ecoului (recomandat)",
    "label_audio_capture_standard": "Standard (latență mai mică)",
}

TRANSLATIONS["ru"] = {
    "section_audio_capture_mode": "Режим захвата звука",
    "audio_capture_mode_description": "Как «Разговор» и «Прямой эфир» записывают и воспроизводят звук. «Подавление эха» не даёт микрофону слышать собственную переведённую речь телефона из динамика, но добавляет обработку и может вызывать задержки или обрывы реплик на некоторых телефонах. «Стандартный» режим быстрее и проще, но используйте его только с наушниками — через динамик он может улавливать собственный звук и переводить его снова по кругу.",
    "label_audio_capture_echo_cancelled": "Подавление эха (рекомендуется)",
    "label_audio_capture_standard": "Стандартный (меньшая задержка)",
}

TRANSLATIONS["sk"] = {
    "section_audio_capture_mode": "Režim zaznamenávania zvuku",
    "audio_capture_mode_description": "Ako Rozhovor a Naživo zaznamenávajú a prehrávajú zvuk. „Potlačenie ozveny“ bráni mikrofónu počuť vlastnú preloženú reč telefónu z reproduktora, no pridáva spracovanie a na niektorých telefónoch môže spôsobiť oneskorenie alebo prerušené repliky. „Štandardný“ je rýchlejší a jednoduchší, ale používajte ho iba so slúchadlami — na reproduktore môže zachytiť vlastný výstup a znova ho preložiť v slučke.",
    "label_audio_capture_echo_cancelled": "Potlačenie ozveny (odporúčané)",
    "label_audio_capture_standard": "Štandardný (nižšia latencia)",
}

TRANSLATIONS["sl"] = {
    "section_audio_capture_mode": "Način zajema zvoka",
    "audio_capture_mode_description": "Kako Pogovor in V živo snemata in predvajata zvok. »Odpravljanje odmeva« mikrofonu preprečuje, da bi slišal lasten preveden govor telefona iz zvočnika, vendar doda obdelavo in lahko na nekaterih telefonih povzroči zamik ali prekinjene replike. »Standardni« je hitrejši in preprostejši, vendar ga uporabljajte le s slušalkami — na zvočniku lahko zazna svoj lastni izhod in ga znova prevede v zanki.",
    "label_audio_capture_echo_cancelled": "Odpravljanje odmeva (priporočeno)",
    "label_audio_capture_standard": "Standardni (manjša zakasnitev)",
}

TRANSLATIONS["sr"] = {
    "section_audio_capture_mode": "Режим снимања звука",
    "audio_capture_mode_description": "Како Разговор и Уживо снимају и репродукују звук. „Уклањање ехоа“ спречава микрофон да чује сопствени преведени говор телефона из звучника, али додаје обраду и на неким телефонима може изазвати кашњење или прекинуте реплике. „Стандардни“ је бржи и једноставнији, али га користите само са слушалицама — на звучнику може ухватити сопствени излаз и поново га превести у петљи.",
    "label_audio_capture_echo_cancelled": "Уклањање ехоа (препоручено)",
    "label_audio_capture_standard": "Стандардни (мања кашњења)",
}

TRANSLATIONS["th"] = {
    "section_audio_capture_mode": "โหมดการจับเสียง",
    "audio_capture_mode_description": "วิธีที่ \"สนทนา\" และ \"สด\" บันทึกและเล่นเสียง \"ตัดเสียงสะท้อน\" ป้องกันไม่ให้ไมโครโฟนได้ยินเสียงพูดที่แปลแล้วของโทรศัพท์เองจากลำโพง แต่เพิ่มการประมวลผลและอาจทำให้เกิดความล่าช้าหรือบทสนทนาขาดหายในโทรศัพท์บางเครื่อง \"มาตรฐาน\" เร็วกว่าและง่ายกว่า แต่ควรใช้กับหูฟังเท่านั้น — บนลำโพงอาจจับเสียงเอาต์พุตของตัวเองแล้วแปลซ้ำวนไป",
    "label_audio_capture_echo_cancelled": "ตัดเสียงสะท้อน (แนะนำ)",
    "label_audio_capture_standard": "มาตรฐาน (ความหน่วงต่ำกว่า)",
}

TRANSLATIONS["tr"] = {
    "section_audio_capture_mode": "Ses yakalama modu",
    "audio_capture_mode_description": "Konuşma ve Canlı, sesi nasıl kaydedip oynatır. \"Yankı giderme\" mikrofonun telefonun kendi çevrilmiş konuşmasını hoparlörden duymasını engeller, ancak işlem ekler ve bazı telefonlarda gecikmeye veya kesilen konuşmalara neden olabilir. \"Standart\" daha hızlı ve basittir, ancak yalnızca kulaklıkla kullanın — hoparlörde kendi çıkışını yakalayıp döngü halinde tekrar çevirebilir.",
    "label_audio_capture_echo_cancelled": "Yankı giderme (önerilir)",
    "label_audio_capture_standard": "Standart (daha düşük gecikme)",
}

TRANSLATIONS["uk"] = {
    "section_audio_capture_mode": "Режим захоплення звуку",
    "audio_capture_mode_description": "Як «Розмова» і «Наживо» записують і відтворюють звук. «Придушення луни» не дає мікрофону чути власне перекладене мовлення телефону з динаміка, але додає обробку і може спричинити затримку або обірвані репліки на деяких телефонах. «Стандартний» швидший і простіший, але використовуйте його лише з навушниками — на динаміку він може вловити власний звук і знову перекласти його по колу.",
    "label_audio_capture_echo_cancelled": "Придушення луни (рекомендовано)",
    "label_audio_capture_standard": "Стандартний (менша затримка)",
}

TRANSLATIONS["vi"] = {
    "section_audio_capture_mode": "Chế độ thu âm thanh",
    "audio_capture_mode_description": "Cách Trò chuyện và Trực tiếp ghi và phát âm thanh. \"Khử tiếng vọng\" ngăn micrô nghe thấy giọng nói đã dịch của chính điện thoại phát ra từ loa, nhưng thêm xử lý và có thể gây độ trễ hoặc ngắt quãng lượt nói trên một số điện thoại. \"Tiêu chuẩn\" nhanh và đơn giản hơn, nhưng chỉ nên dùng với tai nghe — trên loa, nó có thể thu lại chính âm thanh đầu ra của mình và dịch lại liên tục.",
    "label_audio_capture_echo_cancelled": "Khử tiếng vọng (khuyên dùng)",
    "label_audio_capture_standard": "Tiêu chuẩn (độ trễ thấp hơn)",
}

TRANSLATIONS["zh"] = {
    "section_audio_capture_mode": "音频采集模式",
    "audio_capture_mode_description": "\"对话\"和\"实时\"如何录制和播放音频。\"回声消除\"可防止麦克风从扬声器中听到手机自己已翻译的语音,但会增加处理开销,在部分手机上可能导致延迟或对话中断。\"标准\"模式更快更简单,但请仅在使用耳机时使用——若使用扬声器,可能会再次拾取自己的输出并循环翻译。",
    "label_audio_capture_echo_cancelled": "回声消除(推荐)",
    "label_audio_capture_standard": "标准(延迟更低)",
}
