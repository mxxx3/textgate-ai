#!/usr/bin/env python3
"""
One-off rename of the two AudioCaptureMode picker labels
(label_audio_capture_echo_cancelled / label_audio_capture_standard) across
all 40 locale strings.xml files.

Unlike the existing vX_Y_..._translations.py + inject_vX_Y_..._translations.py
scripts (which only ever INSERT brand-new string keys, failing loudly if a
key is unexpectedly missing), this script REPLACES the inner text of two
keys that already exist in every locale file. It fails loudly if either key
is missing from a locale file, since a silent no-op would leave that locale
showing the old label with no error.

Run once, from the repo root:
    python3 scripts/rename_audio_capture_labels.py
"""
import re
import sys
from pathlib import Path

RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

# code -> (new echo-cancelled-slot text, new standard-slot text)
# "values" (no suffix) is English; keys mirror Languages.kt's `code` field
# used as the values-<code>/ resource-directory suffix.
TRANSLATIONS = {
    "":       ("Multimedia speaker", "Call speaker"),          # values/ (English, base/default)
    "pl":     ("Głośnik multimedialny", "Głośnik rozmowy"),     # user's literal requested text
    "ar":     ("مكبر صوت الوسائط", "مكبر صوت المكالمات"),
    "bg":     ("Мултимедиен високоговорител", "Говорител за разговори"),
    "ca":     ("Altaveu multimèdia", "Altaveu de trucada"),
    "cs":     ("Multimediální reproduktor", "Hovorový reproduktor"),
    "da":     ("Medie-højttaler", "Opkalds-højttaler"),
    "de":     ("Multimedia-Lautsprecher", "Gesprächslautsprecher"),
    "el":     ("Ηχείο πολυμέσων", "Ηχείο κλήσης"),
    "es":     ("Altavoz multimedia", "Altavoz de llamada"),
    "et":     ("Multimeediumikõlar", "Kõnekõlar"),
    "fa":     ("بلندگوی رسانه", "بلندگوی تماس"),
    "fi":     ("Multimediakaiutin", "Puhelukaiutin"),
    # Apostrophe must be escaped (\') in Android string resources — an
    # unescaped ' broke the v2.0.1 CI build with "Invalid unicode escape
    # sequence in string" / aapt "Can not extract resource" on values-fr.
    "fr":     ("Haut-parleur multimédia", "Haut-parleur d\\'appel"),
    "hi":     ("मीडिया स्पीकर", "कॉल स्पीकर"),
    "hr":     ("Multimedijski zvučnik", "Zvučnik za pozive"),
    "hu":     ("Médiahangszóró", "Híváshangszóró"),
    "in":     ("Speaker media", "Speaker panggilan"),
    "it":     ("Altoparlante multimediale", "Altoparlante per chiamate"),
    "iw":     ("רמקול מדיה", "רמקול שיחות"),
    "ja":     ("メディアスピーカー", "通話スピーカー"),
    "ko":     ("미디어 스피커", "통화 스피커"),
    "lt":     ("Medijos garsiakalbis", "Skambučių garsiakalbis"),
    "lv":     ("Multivides skaļrunis", "Zvana skaļrunis"),
    "ms":     ("Pembesar suara media", "Pembesar suara panggilan"),
    "nb":     ("Mediehøyttaler", "Samtalehøyttaler"),
    "nl":     ("Mediaspeaker", "Gespreksspeaker"),
    "pt":     ("Altifalante multimédia", "Altifalante de chamada"),
    "pt-rBR": ("Alto-falante de mídia", "Alto-falante de chamada"),
    "ro":     ("Difuzor multimedia", "Difuzor pentru apeluri"),
    "ru":     ("Мультимедийный динамик", "Динамик для звонков"),
    "sk":     ("Multimediálny reproduktor", "Hovorový reproduktor"),
    "sl":     ("Večpredstavnostni zvočnik", "Klicni zvočnik"),
    "sr":     ("Мултимедијални звучник", "Звучник за позиве"),
    "th":     ("ลำโพงมัลติมีเดีย", "ลำโพงสนทนา"),
    "tr":     ("Multimedya hoparlörü", "Arama hoparlörü"),
    "uk":     ("Мультимедійний динамік", "Динамік для дзвінків"),
    "vi":     ("Loa đa phương tiện", "Loa cuộc gọi"),
    "zh":     ("媒体扬声器", "通话扬声器"),
    "zh-rCN": ("媒体扬声器", "通话扬声器"),
}

KEYS = {
    "label_audio_capture_echo_cancelled": 0,
    "label_audio_capture_standard": 1,
}


def replace_value(xml_text: str, key: str, new_value: str, locale_dir: str) -> str:
    pattern = re.compile(
        r'(<string name="' + re.escape(key) + r'">)(.*?)(</string>)',
        re.DOTALL,
    )
    match = pattern.search(xml_text)
    if not match:
        raise SystemExit(f"FATAL: key '{key}' not found in values{('-' + locale_dir) if locale_dir else ''}/strings.xml")
    return pattern.sub(lambda m: m.group(1) + new_value + m.group(3), xml_text, count=1)


def main():
    changed = 0
    for locale_dir, (echo_text, standard_text) in TRANSLATIONS.items():
        dir_name = f"values-{locale_dir}" if locale_dir else "values"
        path = RES_DIR / dir_name / "strings.xml"
        if not path.exists():
            raise SystemExit(f"FATAL: missing file {path}")
        xml_text = path.read_text(encoding="utf-8")
        xml_text = replace_value(xml_text, "label_audio_capture_echo_cancelled", echo_text, locale_dir)
        xml_text = replace_value(xml_text, "label_audio_capture_standard", standard_text, locale_dir)
        path.write_text(xml_text, encoding="utf-8")
        changed += 1
    print(f"Updated {changed} strings.xml files.")


if __name__ == "__main__":
    main()
