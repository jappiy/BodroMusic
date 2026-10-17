"""Нормализация названий для сравнения: регистр, «ё», пунктуация, feat/скобки."""

from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher

_BRACKETS = re.compile(r"[\(\[\{].*?[\)\]\}]")
_FEAT = re.compile(r"\b(feat|ft|featuring|при уч|при участии)\b\.?.*$", re.IGNORECASE)
_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)
_SPACES = re.compile(r"\s+")


def normalize(value: str | None, *, strip_extras: bool = True) -> str:
    """`Трофим — Город Сочи (Remastered) feat. X` → `трофим город сочи`."""
    if not value:
        return ""
    text = unicodedata.normalize("NFKC", value).lower().replace("ё", "е")
    if strip_extras:
        text = _BRACKETS.sub(" ", text)
        text = _FEAT.sub(" ", text)
    text = _NON_WORD.sub(" ", text).replace("_", " ")
    return _SPACES.sub(" ", text).strip()


def tokens(value: str | None) -> list[str]:
    return [t for t in normalize(value).split(" ") if t]


def similarity(a: str | None, b: str | None) -> float:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    return SequenceMatcher(None, na, nb).ratio()


def contains_all(haystack: str, needle_tokens: list[str]) -> bool:
    """Все слова есть в строке как отдельные слова (строка уже нормализована)."""
    words = set(haystack.split(" "))
    return all(t in words for t in needle_tokens)


def search_text(*parts: str | None) -> str:
    """Текст запроса для Soulseek: без пунктуации и скобок (сеть плохо ищет по спецсимволам)."""
    return " ".join(normalize(p) for p in parts if p).strip()


def search_queries(artist: str, title: str) -> list[str]:
    """До трёх запросов к Soulseek, от точного к широкому.

    1. «исполнитель название»; 2. только основной исполнитель (без feat., второстепенных и списков через запятую);
    3. транслит (для кириллицы) или одно название без исполнителя. Скобки и пунктуация не нужны ни в одном.
    """
    main = main_artist(artist) or artist
    first = search_text(artist, title)
    second = search_text(main, title)
    if has_cyrillic(second):
        third = translit(second, fold=False)
    else:
        third = search_text(title) if len(tokens(title)) >= 2 else ""
    result: list[str] = []
    for query in (first, second, third):
        if query and query not in result:
            result.append(query)
    return result


_TRANSLIT = str.maketrans(
    {
        "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e", "ж": "zh", "з": "z", "и": "i",
        "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t",
        "у": "u", "ф": "f", "х": "h", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sch", "ъ": "", "ы": "y", "ь": "",
        "э": "e", "ю": "yu", "я": "ya",
    }
)  # fmt: skip
# Латинские написания, которые транслит пишет иначе: Kirkorow/Kirkorov, Tsoy/Tsoi, Yuri/Iurii — к одному виду.
_LATIN_FOLD = ((re.compile(r"(?<=[aeiou])y\b"), "i"), (re.compile(r"w"), "v"), (re.compile(r"kh"), "h"))


def translit(value: str | None, *, fold: bool = True) -> str:
    """Нормализованная строка латиницей: `Филипп Киркоров` и `Filipp Kirkorov` дают одно и то же.

    `fold=False` — обычный транслит без сведения вариантов написания (для текста поискового запроса).
    """
    return to_latin(normalize(value), fold=fold)


def to_latin(normalized: str, *, fold: bool = True) -> str:
    """Транслит уже нормализованной строки (без повторного отрезания скобок и feat)."""
    text = normalized.translate(_TRANSLIT)
    if fold:
        for pattern, repl in _LATIN_FOLD:
            text = pattern.sub(repl, text)
    return text


def has_cyrillic(value: str | None) -> bool:
    return any("а" <= ch <= "я" or ch == "ё" for ch in (value or "").lower())


def similarity_any(a: str | None, b: str | None) -> float:
    """Похожесть с учётом транслита: кириллица и латиница сравниваются в одном алфавите."""
    direct = similarity(a, b)
    if direct >= 1.0 or not a or not b:
        return direct
    ta, tb = translit(a), translit(b)
    if not ta or not tb:
        return direct
    return max(direct, 1.0 if ta == tb else SequenceMatcher(None, ta, tb).ratio())


# «A feat. B», «A & B», «A, B», «A x B», «A и B», «A при уч. B» — основной исполнитель A.
_ARTIST_SPLIT = re.compile(
    r"\s*(?:,|;|/|\s&\s|\s\+\s|\s(?:feat|ft|featuring|vs|x|и|при уч|при участии)\b\.?)\s*", re.IGNORECASE
)


def main_artist(artist: str | None) -> str:
    """Основной исполнитель без feat., второстепенных и списков через запятую."""
    return _ARTIST_SPLIT.split((artist or "").strip(), maxsplit=1)[0].strip()


def same_artist(a: str | None, b: str | None) -> bool:
    """Тот же исполнитель с точностью до регистра, «ё», feat./&/списков и транслита."""
    if not normalize(a) or not normalize(b):
        return False
    for x in (a, main_artist(a)):
        for y in (b, main_artist(b)):
            if normalize(x) == normalize(y) or translit(x) == translit(y) or similarity_any(x, y) >= 0.9:
                return True
    names_a = {translit(n) for n in _ARTIST_SPLIT.split(a or "") if normalize(n)}
    names_b = {translit(n) for n in _ARTIST_SPLIT.split(b or "") if normalize(n)}
    return bool(names_a & names_b)
