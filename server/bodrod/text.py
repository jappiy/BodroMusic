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
