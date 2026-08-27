# -*- coding: utf-8 -*-
"""
Injects the v2.x Live routing/error-handling string translations
(v2_x_error_routing_translations.py) into every values-XX/strings.xml
locale file that is missing them — same purpose and escaping rules as
inject_v2_0_1_audio_capture_translations.py, scoped to the 5 new keys this
update added (live_ambient_language_auto, live_detected_language_format,
live_error_quota, live_error_auth, live_error_config), so CI's
MissingTranslation lint Error (lint.abortOnError = true) doesn't fire for
locales other than values/ (English, source of truth for key order) and
values-pl/ (added directly, matching this project's own primary-language
convention).

Run from the repo root: python3 scripts/inject_v2_x_error_routing_translations.py
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from v2_x_error_routing_translations import TRANSLATIONS  # noqa: E402

REPO_ROOT = Path(__file__).parent.parent
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"

NEW_KEYS = [
    "live_ambient_language_auto",
    "live_detected_language_format",
    "live_error_quota",
    "live_error_auth",
    "live_error_config",
]

# locale folder suffix -> which TRANSLATIONS entry to use (identical mapping
# to inject_v2_0_1_audio_capture_translations.py, including the
# pt-rBR/zh-rCN sharing convention)
LOCALE_TO_TRANSLATION_KEY = {
    "ar": "ar", "bg": "bg", "ca": "ca", "cs": "cs", "da": "da", "de": "de",
    "el": "el", "es": "es", "et": "et", "fa": "fa", "fi": "fi", "fr": "fr",
    "hi": "hi", "hr": "hr", "hu": "hu", "in": "in", "it": "it", "iw": "iw",
    "ja": "ja", "ko": "ko", "lt": "lt", "lv": "lv", "ms": "ms", "nb": "nb",
    "nl": "nl", "pt": "pt", "pt-rBR": "pt", "ro": "ro", "ru": "ru",
    "sk": "sk", "sl": "sl", "sr": "sr", "th": "th", "tr": "tr", "uk": "uk",
    "vi": "vi", "zh": "zh", "zh-rCN": "zh",
}


def xml_escape(value: str) -> str:
    value = value.replace("&", "&amp;")
    value = value.replace("<", "&lt;")
    value = value.replace(">", "&gt;")
    value = value.replace("'", "\\'")
    value = value.replace('"', '\\"')
    if value.startswith("?") or value.startswith("@"):
        value = "\\" + value
    return value


def existing_keys(content: str) -> set:
    return set(re.findall(r'<string name="([^"]+)"', content))


def main():
    total_inserted = 0
    for locale_dir, translation_key in LOCALE_TO_TRANSLATION_KEY.items():
        strings_path = RES_DIR / f"values-{locale_dir}" / "strings.xml"
        if not strings_path.exists():
            print(f"SKIP (no such file): {strings_path}")
            continue

        content = strings_path.read_text(encoding="utf-8")
        have = existing_keys(content)
        translations = TRANSLATIONS[translation_key]

        missing_here = [k for k in NEW_KEYS if k not in have]
        if not missing_here:
            print(f"{locale_dir}: nothing missing, skipping")
            continue

        lines = ["    <!-- v2.x: Live routing/detected-language/error-category strings -->"]
        not_found = []
        for key in missing_here:
            if key not in translations:
                not_found.append(key)
                continue
            escaped = xml_escape(translations[key])
            lines.append(f'    <string name="{key}">{escaped}</string>')

        if not_found:
            raise SystemExit(
                f"ERROR: {locale_dir} is missing translations for: {not_found} "
                "in v2_x_error_routing_translations.py - aborting so nothing is silently skipped."
            )

        insertion = "\n".join(lines) + "\n"
        if "</resources>" not in content:
            raise SystemExit(f"ERROR: {strings_path} has no </resources> closing tag")

        new_content = content.replace("</resources>", insertion + "</resources>")
        strings_path.write_text(new_content, encoding="utf-8")
        total_inserted += len(missing_here)
        print(f"{locale_dir}: inserted {len(missing_here)} strings")

    print(f"\nDone. Inserted {total_inserted} strings total.")


if __name__ == "__main__":
    main()
