"""Ключи сопоставления треков из разных каталогов с библиотекой, пулом и чёрными списками.

`Город Сочи (Remastered 2011)` у Яндекса, `Gorod Sochi - Remastered` у Last.fm и файл `Трофим - Город Сочи` в
библиотеке должны дать один ключ: регистр, «ё», скобки, feat, пометка remaster, второстепенные исполнители и алфавит
(кириллица/латиница) не учитываются.
"""

from __future__ import annotations

import re

from ..text import main_artist, normalize, to_latin

_REMASTER = re.compile(r"\s(?:\d{4}\s)?remaster(?:ed)?(?:\s.*)?$|\s(?:remaster(?:ed)?|ремастер)\s\d{4}.*$")
_VERSION_TAIL = re.compile(r"\s(?:radio edit|single version|album version|original mix|mono|stereo)$")


def clean_title(title: str | None) -> str:
    text = normalize(title)
    text = _REMASTER.sub("", f" {text}").strip()
    return _VERSION_TAIL.sub("", f" {text}").strip()


def artist_key(artist: str | None) -> str:
    return to_latin(normalize(main_artist(artist) or artist))


def track_key(artist: str | None, title: str | None) -> str:
    """Ключ «основной исполнитель + название» в одном алфавите."""
    return f"{artist_key(artist)}\x1f{to_latin(clean_title(title))}"
