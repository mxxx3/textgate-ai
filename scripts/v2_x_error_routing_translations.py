# -*- coding: utf-8 -*-
# Real, per-language translations for the 5 new string keys added by the
# v2.x Live routing/latency/error-handling request:
#   - live_ambient_language_auto (static "Automatic detection" label,
#     replacing the removed ambient-language picker — see LiveTabController)
#   - live_detected_language_format ("Detected: %1$s" — the language Gemini
#     itself reported via inputTranscription.languageCode)
#   - live_error_quota / live_error_auth / live_error_config (the three
#     new, non-reconnecting Live error categories — see
#     GeminiLiveClient.LiveErrorCategory and LiveErrorMessages.kt)
#
# Same reasoning and convention as v2_translations.py /
# v2_0_1_audio_capture_translations.py: this project's lint config
# (`abortOnError = true`) treats MissingTranslation as a build-breaking
# Error, not a warning, so every new key must be translated into all 37
# non-English/non-Polish locales, not just left to Android's default-locale
# runtime fallback.
#
# Values are plain (unescaped) text. inject_v2_x_error_routing_translations.py
# performs the Android string-resource XML escaping (&, <, >, leading @/?,
# apostrophes, quotes) when writing these into each values-XX/strings.xml.
# "Gemini Live" and "API" are deliberately left untranslated (proper
# noun/technical term), matching how existing keys in this project already
# treat "Gemini"/"API".

TRANSLATIONS = {}

TRANSLATIONS["ar"] = {
    "live_ambient_language_auto": "الكشف التلقائي",
    "live_detected_language_format": "تم الكشف عن: %1$s",
    "live_error_quota": "تم الوصول إلى حد Gemini Live — يرجى المحاولة مرة أخرى لاحقًا.",
    "live_error_auth": "مشكلة في مفتاح API — تحقق من المفتاح في الإعدادات.",
    "live_error_config": "تكوين الجلسة غير صالح (مثل لغة غير مدعومة).",
}

TRANSLATIONS["bg"] = {
    "live_ambient_language_auto": "Автоматично разпознаване",
    "live_detected_language_format": "Разпознат: %1$s",
    "live_error_quota": "Достигнат е лимитът на Gemini Live — моля, опитайте отново по-късно.",
    "live_error_auth": "Проблем с API ключа — проверете ключа си в Настройки.",
    "live_error_config": "Невалидна конфигурация на сесията (напр. неподдържан език).",
}

TRANSLATIONS["ca"] = {
    "live_ambient_language_auto": "Detecció automàtica",
    "live_detected_language_format": "Detectat: %1$s",
    "live_error_quota": "S'ha arribat al límit de Gemini Live — torneu-ho a provar més tard.",
    "live_error_auth": "Problema amb la clau API — comproveu la clau a Configuració.",
    "live_error_config": "Configuració de sessió no vàlida (p. ex. idioma no compatible).",
}

TRANSLATIONS["cs"] = {
    "live_ambient_language_auto": "Automatická detekce",
    "live_detected_language_format": "Rozpoznáno: %1$s",
    "live_error_quota": "Dosažen limit Gemini Live — zkuste to prosím znovu později.",
    "live_error_auth": "Problém s klíčem API — zkontrolujte klíč v Nastavení.",
    "live_error_config": "Neplatná konfigurace relace (např. nepodporovaný jazyk).",
}

TRANSLATIONS["da"] = {
    "live_ambient_language_auto": "Automatisk registrering",
    "live_detected_language_format": "Registreret: %1$s",
    "live_error_quota": "Gemini Live-grænsen er nået — prøv igen senere.",
    "live_error_auth": "Problem med API-nøgle — tjek din nøgle i Indstillinger.",
    "live_error_config": "Ugyldig sessionskonfiguration (f.eks. et sprog der ikke understøttes).",
}

TRANSLATIONS["de"] = {
    "live_ambient_language_auto": "Automatische Erkennung",
    "live_detected_language_format": "Erkannt: %1$s",
    "live_error_quota": "Gemini Live-Limit erreicht — bitte später erneut versuchen.",
    "live_error_auth": "Problem mit dem API-Schlüssel — überprüfe deinen Schlüssel in den Einstellungen.",
    "live_error_config": "Ungültige Sitzungskonfiguration (z. B. nicht unterstützte Sprache).",
}

TRANSLATIONS["el"] = {
    "live_ambient_language_auto": "Αυτόματος εντοπισμός",
    "live_detected_language_format": "Εντοπίστηκε: %1$s",
    "live_error_quota": "Το όριο του Gemini Live έχει εξαντληθεί — δοκιμάστε ξανά αργότερα.",
    "live_error_auth": "Πρόβλημα με το κλειδί API — ελέγξτε το κλειδί σας στις Ρυθμίσεις.",
    "live_error_config": "Μη έγκυρη διαμόρφωση περιόδου σύνδεσης (π.χ. μη υποστηριζόμενη γλώσσα).",
}

TRANSLATIONS["es"] = {
    "live_ambient_language_auto": "Detección automática",
    "live_detected_language_format": "Detectado: %1$s",
    "live_error_quota": "Se alcanzó el límite de Gemini Live — inténtalo de nuevo más tarde.",
    "live_error_auth": "Problema con la clave API — comprueba tu clave en Ajustes.",
    "live_error_config": "Configuración de sesión no válida (p. ej., idioma no compatible).",
}

TRANSLATIONS["et"] = {
    "live_ambient_language_auto": "Automaatne tuvastus",
    "live_detected_language_format": "Tuvastatud: %1$s",
    "live_error_quota": "Gemini Live'i piirang on saavutatud — proovi hiljem uuesti.",
    "live_error_auth": "API-võtme probleem — kontrolli oma võtit Seadete alt.",
    "live_error_config": "Sobimatu seansi seadistus (nt toetamata keel).",
}

TRANSLATIONS["fa"] = {
    "live_ambient_language_auto": "تشخیص خودکار",
    "live_detected_language_format": "شناسایی‌شده: %1$s",
    "live_error_quota": "به محدودیت Gemini Live رسیده‌اید — لطفاً بعداً دوباره امتحان کنید.",
    "live_error_auth": "مشکل کلید API — کلید خود را در تنظیمات بررسی کنید.",
    "live_error_config": "پیکربندی نشست نامعتبر است (مثلاً زبان پشتیبانی‌نشده).",
}

TRANSLATIONS["fi"] = {
    "live_ambient_language_auto": "Automaattinen tunnistus",
    "live_detected_language_format": "Tunnistettu: %1$s",
    "live_error_quota": "Gemini Live -raja saavutettu — yritä myöhemmin uudelleen.",
    "live_error_auth": "API-avaimen ongelma — tarkista avaimesi Asetuksista.",
    "live_error_config": "Virheellinen istunnon määritys (esim. kieltä ei tueta).",
}

TRANSLATIONS["fr"] = {
    "live_ambient_language_auto": "Détection automatique",
    "live_detected_language_format": "Détecté : %1$s",
    "live_error_quota": "Limite Gemini Live atteinte — veuillez réessayer plus tard.",
    "live_error_auth": "Problème de clé API — vérifiez votre clé dans les Paramètres.",
    "live_error_config": "Configuration de session invalide (p. ex. langue non prise en charge).",
}

TRANSLATIONS["hi"] = {
    "live_ambient_language_auto": "स्वचालित पहचान",
    "live_detected_language_format": "पहचाना गया: %1$s",
    "live_error_quota": "Gemini Live सीमा पूरी हो गई — कृपया बाद में फिर से प्रयास करें।",
    "live_error_auth": "API कुंजी की समस्या — सेटिंग्स में अपनी कुंजी जांचें।",
    "live_error_config": "सत्र कॉन्फ़िगरेशन अमान्य है (जैसे असमर्थित भाषा)।",
}

TRANSLATIONS["hr"] = {
    "live_ambient_language_auto": "Automatsko prepoznavanje",
    "live_detected_language_format": "Prepoznato: %1$s",
    "live_error_quota": "Dosegnut je limit Gemini Live — pokušajte ponovno kasnije.",
    "live_error_auth": "Problem s API ključem — provjerite ključ u Postavkama.",
    "live_error_config": "Nevažeća konfiguracija sesije (npr. nepodržani jezik).",
}

TRANSLATIONS["hu"] = {
    "live_ambient_language_auto": "Automatikus felismerés",
    "live_detected_language_format": "Felismerve: %1$s",
    "live_error_quota": "Elérted a Gemini Live limitjét — próbáld újra később.",
    "live_error_auth": "API-kulcs probléma — ellenőrizd a kulcsot a Beállításokban.",
    "live_error_config": "Érvénytelen munkamenet-konfiguráció (pl. nem támogatott nyelv).",
}

TRANSLATIONS["in"] = {
    "live_ambient_language_auto": "Deteksi otomatis",
    "live_detected_language_format": "Terdeteksi: %1$s",
    "live_error_quota": "Batas Gemini Live tercapai — coba lagi nanti.",
    "live_error_auth": "Masalah kunci API — periksa kunci Anda di Pengaturan.",
    "live_error_config": "Konfigurasi sesi tidak valid (mis. bahasa tidak didukung).",
}

TRANSLATIONS["it"] = {
    "live_ambient_language_auto": "Rilevamento automatico",
    "live_detected_language_format": "Rilevata: %1$s",
    "live_error_quota": "Limite di Gemini Live raggiunto — riprova più tardi.",
    "live_error_auth": "Problema con la chiave API — controlla la chiave nelle Impostazioni.",
    "live_error_config": "Configurazione di sessione non valida (es. lingua non supportata).",
}

TRANSLATIONS["iw"] = {
    "live_ambient_language_auto": "זיהוי אוטומטי",
    "live_detected_language_format": "זוהתה: %1$s",
    "live_error_quota": "הגעת למגבלת Gemini Live — נסה שוב מאוחר יותר.",
    "live_error_auth": "בעיה במפתח API — בדוק את המפתח שלך בהגדרות.",
    "live_error_config": "תצורת הפעלה לא תקינה (למשל שפה שאינה נתמכת).",
}

TRANSLATIONS["ja"] = {
    "live_ambient_language_auto": "自動検出",
    "live_detected_language_format": "検出: %1$s",
    "live_error_quota": "Gemini Liveの上限に達しました — しばらくしてから再試行してください。",
    "live_error_auth": "APIキーに問題があります — 設定でキーを確認してください。",
    "live_error_config": "セッション設定が無効です(例:サポートされていない言語)。",
}

TRANSLATIONS["ko"] = {
    "live_ambient_language_auto": "자동 감지",
    "live_detected_language_format": "감지됨: %1$s",
    "live_error_quota": "Gemini Live 한도에 도달했습니다 — 나중에 다시 시도해 주세요.",
    "live_error_auth": "API 키 문제 — 설정에서 키를 확인하세요.",
    "live_error_config": "세션 구성이 잘못되었습니다(예: 지원되지 않는 언어).",
}

TRANSLATIONS["lt"] = {
    "live_ambient_language_auto": "Automatinis aptikimas",
    "live_detected_language_format": "Aptikta: %1$s",
    "live_error_quota": "Pasiekta „Gemini Live“ riba — bandykite dar kartą vėliau.",
    "live_error_auth": "API rakto problema — patikrinkite raktą Nustatymuose.",
    "live_error_config": "Neteisinga sesijos konfigūracija (pvz., nepalaikoma kalba).",
}

TRANSLATIONS["lv"] = {
    "live_ambient_language_auto": "Automātiska noteikšana",
    "live_detected_language_format": "Noteikts: %1$s",
    "live_error_quota": "Sasniegts Gemini Live limits — mēģiniet vēlreiz vēlāk.",
    "live_error_auth": "API atslēgas problēma — pārbaudiet atslēgu Iestatījumos.",
    "live_error_config": "Nederīga sesijas konfigurācija (piem., neatbalstīta valoda).",
}

TRANSLATIONS["ms"] = {
    "live_ambient_language_auto": "Pengesanan automatik",
    "live_detected_language_format": "Dikesan: %1$s",
    "live_error_quota": "Had Gemini Live telah dicapai — sila cuba lagi kemudian.",
    "live_error_auth": "Masalah kunci API — semak kunci anda dalam Tetapan.",
    "live_error_config": "Konfigurasi sesi tidak sah (cth. bahasa tidak disokong).",
}

TRANSLATIONS["nb"] = {
    "live_ambient_language_auto": "Automatisk gjenkjenning",
    "live_detected_language_format": "Gjenkjent: %1$s",
    "live_error_quota": "Gemini Live-grensen er nådd — prøv igjen senere.",
    "live_error_auth": "Problem med API-nøkkel — sjekk nøkkelen din i Innstillinger.",
    "live_error_config": "Ugyldig øktkonfigurasjon (f.eks. et språk som ikke støttes).",
}

TRANSLATIONS["nl"] = {
    "live_ambient_language_auto": "Automatische detectie",
    "live_detected_language_format": "Gedetecteerd: %1$s",
    "live_error_quota": "Gemini Live-limiet bereikt — probeer het later opnieuw.",
    "live_error_auth": "Probleem met API-sleutel — controleer je sleutel in Instellingen.",
    "live_error_config": "Ongeldige sessieconfiguratie (bijv. niet-ondersteunde taal).",
}

TRANSLATIONS["pt"] = {
    "live_ambient_language_auto": "Deteção automática",
    "live_detected_language_format": "Detetado: %1$s",
    "live_error_quota": "Limite do Gemini Live atingido — tente novamente mais tarde.",
    "live_error_auth": "Problema com a chave API — verifique a sua chave nas Definições.",
    "live_error_config": "Configuração de sessão inválida (por ex., idioma não suportado).",
}

TRANSLATIONS["ro"] = {
    "live_ambient_language_auto": "Detectare automată",
    "live_detected_language_format": "Detectat: %1$s",
    "live_error_quota": "Limita Gemini Live a fost atinsă — încearcă din nou mai târziu.",
    "live_error_auth": "Problemă cu cheia API — verifică cheia în Setări.",
    "live_error_config": "Configurație de sesiune nevalidă (de ex. limbă neacceptată).",
}

TRANSLATIONS["ru"] = {
    "live_ambient_language_auto": "Автоматическое определение",
    "live_detected_language_format": "Определено: %1$s",
    "live_error_quota": "Достигнут лимит Gemini Live — повторите попытку позже.",
    "live_error_auth": "Проблема с ключом API — проверьте ключ в Настройках.",
    "live_error_config": "Недопустимая конфигурация сеанса (например, неподдерживаемый язык).",
}

TRANSLATIONS["sk"] = {
    "live_ambient_language_auto": "Automatické rozpoznávanie",
    "live_detected_language_format": "Rozpoznané: %1$s",
    "live_error_quota": "Dosiahnutý limit Gemini Live — skúste to znova neskôr.",
    "live_error_auth": "Problém s kľúčom API — skontrolujte kľúč v Nastaveniach.",
    "live_error_config": "Neplatná konfigurácia relácie (napr. nepodporovaný jazyk).",
}

TRANSLATIONS["sl"] = {
    "live_ambient_language_auto": "Samodejno zaznavanje",
    "live_detected_language_format": "Zaznano: %1$s",
    "live_error_quota": "Dosežena je omejitev Gemini Live — poskusite znova pozneje.",
    "live_error_auth": "Težava s ključem API — preverite ključ v Nastavitvah.",
    "live_error_config": "Neveljavna konfiguracija seje (npr. nepodprt jezik).",
}

TRANSLATIONS["sr"] = {
    "live_ambient_language_auto": "Аутоматско препознавање",
    "live_detected_language_format": "Препознато: %1$s",
    "live_error_quota": "Достигнут је лимит Gemini Live — покушајте поново касније.",
    "live_error_auth": "Проблем са API кључем — проверите кључ у Подешавањима.",
    "live_error_config": "Неважећа конфигурација сесије (нпр. неподржани језик).",
}

TRANSLATIONS["th"] = {
    "live_ambient_language_auto": "ตรวจจับอัตโนมัติ",
    "live_detected_language_format": "ตรวจพบ: %1$s",
    "live_error_quota": "ถึงขีดจำกัดของ Gemini Live แล้ว — โปรดลองอีกครั้งภายหลัง",
    "live_error_auth": "ปัญหาคีย์ API — ตรวจสอบคีย์ของคุณในการตั้งค่า",
    "live_error_config": "การกำหนดค่าเซสชันไม่ถูกต้อง (เช่น ภาษาที่ไม่รองรับ)",
}

TRANSLATIONS["tr"] = {
    "live_ambient_language_auto": "Otomatik algılama",
    "live_detected_language_format": "Algılandı: %1$s",
    "live_error_quota": "Gemini Live sınırına ulaşıldı — lütfen daha sonra tekrar deneyin.",
    "live_error_auth": "API anahtarı sorunu — anahtarınızı Ayarlar'da kontrol edin.",
    "live_error_config": "Geçersiz oturum yapılandırması (örn. desteklenmeyen dil).",
}

TRANSLATIONS["uk"] = {
    "live_ambient_language_auto": "Автоматичне визначення",
    "live_detected_language_format": "Визначено: %1$s",
    "live_error_quota": "Досягнуто ліміту Gemini Live — спробуйте пізніше.",
    "live_error_auth": "Проблема з ключем API — перевірте ключ у Налаштуваннях.",
    "live_error_config": "Недійсна конфігурація сеансу (напр., непідтримувана мова).",
}

TRANSLATIONS["vi"] = {
    "live_ambient_language_auto": "Tự động phát hiện",
    "live_detected_language_format": "Đã phát hiện: %1$s",
    "live_error_quota": "Đã đạt giới hạn Gemini Live — vui lòng thử lại sau.",
    "live_error_auth": "Sự cố khóa API — kiểm tra khóa của bạn trong Cài đặt.",
    "live_error_config": "Cấu hình phiên không hợp lệ (ví dụ: ngôn ngữ không được hỗ trợ).",
}

TRANSLATIONS["zh"] = {
    "live_ambient_language_auto": "自动检测",
    "live_detected_language_format": "检测到:%1$s",
    "live_error_quota": "已达到 Gemini Live 限额 — 请稍后再试。",
    "live_error_auth": "API 密钥问题 — 请在设置中检查您的密钥。",
    "live_error_config": "会话配置无效(例如不支持的语言)。",
}
