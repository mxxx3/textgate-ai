#!/usr/bin/env python3
"""
Fixes `audio_capture_mode_description` in every locale's strings.xml: the
sentence still named the two AudioCaptureMode options by their OLD labels
("Echo-cancelled"/"Redukcja echa" and "Standard"/"Standardowy") after
scripts/rename_audio_capture_labels.py renamed the options themselves to
"Multimedia speaker"/"Głośnik multimedialny" and "Call speaker"/
"Głośnik rozmowy" (etc. per locale) — the description text was never
touched by that rename, so it went stale and now names options that no
longer exist in the picker directly above it.

Each locale's description names things inside a locale-appropriate quote
style — most use straight \"...\" (escaped in XML), others «...», »...«
(Slovenian, reversed), „...“ / „...” (German/Slavic-style), or 「...」
(Japanese). Several locales ALSO quote the two TAB names ("Rozmowa"/
"Na żywo") earlier in the same sentence using the identical quote style,
so a locale's description can contain either exactly 2 quoted spans (just
the two mode names) or exactly 4 (tab name, tab name, mode name, mode
name) — confirmed by inspecting every one of the 40 files before writing
this script. In both cases the mode names are always the LAST two quoted
spans, so this script takes the last two matches of whichever quote-pair
yields 2 or 4 total, and replaces only the text inside those two spans
with the already-renamed label text from scripts/rename_audio_capture_
labels.py's TRANSLATIONS table — preserving each locale's own quote
characters, tab-name quoting (if any), and the rest of the sentence
untouched.

Run once, from the repo root:
    python3 scripts/fix_audio_capture_mode_description.py
"""
import re
import sys
from pathlib import Path

RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rename_audio_capture_labels import TRANSLATIONS  # noqa: E402

KEY = "audio_capture_mode_description"

# Ordered candidate (open, close) quote-character pairs to try per locale.
# Tried in this order; the first pair yielding exactly 2 or 4 matches wins
# (an odd count, e.g. 1 or 3, means we matched the WRONG quote direction —
# e.g. trying «...» against text that actually uses »...« reversed
# guillemets — so those are skipped rather than used).
QUOTE_PAIRS = [
    (r'\\"', r'\\"'),   # escaped straight quotes: \"...\"
    ('«', '»'),         # French/Russian/Ukrainian/Persian guillemets
    ('»', '«'),         # Slovenian reversed guillemets
    ('„', '“'),         # German/Bulgarian/Czech/Slovak/Lithuanian/Serbian
    ('„', '”'),         # Romanian
    ('「', '」'),        # Japanese corner brackets
]


def find_quoted_spans(value: str):
    for open_c, close_c in QUOTE_PAIRS:
        pattern = re.compile(open_c + r'(.*?)' + close_c, re.DOTALL)
        matches = list(pattern.finditer(value))
        if len(matches) in (2, 4):
            return open_c, close_c, matches[-2:]  # mode names are always last
    return None


def main():
    changed = 0
    skipped = []
    for locale_dir, (echo_text, standard_text) in TRANSLATIONS.items():
        dir_name = f"values-{locale_dir}" if locale_dir else "values"
        path = RES_DIR / dir_name / "strings.xml"
        if not path.exists():
            raise SystemExit(f"FATAL: missing file {path}")
        xml_text = path.read_text(encoding="utf-8")

        m = re.search(r'<string name="' + KEY + r'">(.*?)</string>', xml_text, re.DOTALL)
        if not m:
            raise SystemExit(f"FATAL: key '{KEY}' not found in {path}")
        value = m.group(1)

        found = find_quoted_spans(value)
        if not found:
            skipped.append(dir_name)
            continue
        open_c, close_c, (echo_match, standard_match) = found

        open_lit = '\\"' if open_c == r'\\"' else open_c
        close_lit = '\\"' if close_c == r'\\"' else close_c

        # Some locales (French confirmed; « ... » throughout that file, per
        # its own typographic convention) put a literal space just inside
        # each guillemet — " Word " rather than "Word". Preserve whatever
        # leading/trailing whitespace the OLD quoted text had, rather than
        # hardcoding it for one locale, so any other locale following the
        # same convention is handled automatically too.
        def reguard(old_inner: str, new_inner: str) -> str:
            leading = old_inner[:len(old_inner) - len(old_inner.lstrip())]
            trailing = old_inner[len(old_inner.rstrip()):]
            return leading + new_inner + trailing

        echo_inner = reguard(echo_match.group(1), echo_text)
        standard_inner = reguard(standard_match.group(1), standard_text)

        new_value = (
            value[:echo_match.start()]
            + open_lit + echo_inner + close_lit
            + value[echo_match.end():standard_match.start()]
            + open_lit + standard_inner + close_lit
            + value[standard_match.end():]
        )

        new_xml_text = xml_text[:m.start(1)] + new_value + xml_text[m.end(1):]
        path.write_text(new_xml_text, encoding="utf-8")
        changed += 1

    print(f"Updated {changed} strings.xml files.")
    if skipped:
        print(f"FATAL: could not find exactly 2 or 4 quoted spans in: {', '.join(skipped)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
