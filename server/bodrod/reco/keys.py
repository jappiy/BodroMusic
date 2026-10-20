"""Ключи сопоставления треков из разных каталогов с библиотекой, пулом и чёрными списками.

`Город Сочи (Remastered 2011)` у Яндекса, `Gorod Sochi - Remastered` у Last.fm и файл `Трофим - Город Сочи` в
библиотеке должны дать один ключ: регистр (casefold), «ё», диакритика латиницы (`ADÉLA` = `Adela`), скобки
(`(with …)`, `(feat. …)`, `[…]`), feat, пометки remaster / radio edit, второстепенные исполнители и алфавит
(кириллица/латиница) не учитываются.

При изменении правил поднимается `KEYS_VERSION` — сохранённые ключи пересчитываются при старте (`reco/store.py`).
"""

from __future__ import annotations

import re
import unicodedata

from ..text import ARTIST_SPLIT, main_artist, normalize, to_latin

KEYS_VERSION = 2

_REMASTER = re.compile(r"\s(?:\d{4}\s)?remaster(?:ed)?(?:\s.*)?$|\s(?:remaster(?:ed)?|ремастер)\s\d{4}.*$")
_VERSION_TAIL = re.compile(
    r"\s(?:radio edit|radio version|radio mix|single edit|single version|album version|original mix|" r"mono|stereo)$"
)


def fold(value: str | None) -> str:
    """NFKC → casefold → без диакритики у латиницы (`É` → `e`); кириллица (`й`) не меняется."""
    if not value:
        return ""
    text = unicodedata.normalize("NFKD", unicodedata.normalize("NFKC", value).casefold())
    out: list[str] = []
    for ch in text:
        if unicodedata.combining(ch) and out and out[-1].isascii():
            continue
        out.append(ch)
    return unicodedata.normalize("NFC", "".join(out))


def clean_title(title: str | None) -> str:
    text = normalize(fold(title))
    text = _REMASTER.sub("", f" {text}").strip()
    return _VERSION_TAIL.sub("", f" {text}").strip()


def artist_key(artist: str | None) -> str:
    folded = fold(artist)
    return to_latin(normalize(main_artist(folded) or folded))


def artist_keys(artist: str | None) -> list[str]:
    """Ключи всех исполнителей из строки `A, B & C feat. D` (основной — первым)."""
    folded = fold(artist)
    keys = [artist_key(folded)]
    for name in ARTIST_SPLIT.split(folded):
        key = to_latin(normalize(name))
        if key and key not in keys:
            keys.append(key)
    return [k for k in keys if k]


def title_key(title: str | None) -> str:
    return to_latin(clean_title(title))


def track_key(artist: str | None, title: str | None) -> str:
    """Ключ «основной исполнитель + название» в одном алфавите."""
    return f"{artist_key(artist)}\x1f{title_key(title)}"


def track_keys(artist: str | None, title: str | None) -> list[str]:
    """Ключи трека для каждого исполнителя из списка (основной — первым)."""
    title_part = title_key(title)
    return [f"{a}\x1f{title_part}" for a in artist_keys(artist)]
