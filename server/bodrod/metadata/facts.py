"""Короткий факт о треке или исполнителе для главной «Моя волна»: одна фраза из Last.fm и теги.

Источник — `track.getInfo` (вики трека, теги трека) и `artist.getInfo` (биография, теги исполнителя). Из вики и
биографии берётся первое предложение без ссылки «Read more on Last.fm». Нет ключа, сети или данных — пустой ответ,
приложение строку не показывает. Ответы кэшируются в памяти на сутки (на главную заходят часто).
"""

from __future__ import annotations

import html
import logging
import re
import time
from collections.abc import Callable
from typing import Any

from .lastfm import LastFmClient, LastFmError

log = logging.getLogger(__name__)

CACHE_TTL_S = 24 * 3600
CACHE_MAX = 2000
MAX_FACT_CHARS = 180
MAX_TAGS = 5
"""Служебные теги Last.fm, которые ничего не говорят о музыке."""
_NOISE_TAGS = {"seen live", "favorites", "favourite", "favorite", "my favorite", "love", "awesome", "beautiful"}
_LINK = re.compile(r"<a [^>]*>.*?</a>", re.IGNORECASE | re.DOTALL)
_TAG = re.compile(r"<[^>]+>")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def first_sentence(raw: str | None) -> str | None:
    """Первое предложение текста Last.fm без HTML и ссылки «Read more»; слишком длинное — обрезано по слову."""
    if not raw:
        return None
    text = _TAG.sub("", _LINK.sub("", raw))
    text = " ".join(html.unescape(text).split())
    if not text:
        return None
    sentence = _SENTENCE_END.split(text, maxsplit=1)[0].strip()
    if len(sentence) > MAX_FACT_CHARS:
        sentence = sentence[:MAX_FACT_CHARS].rsplit(" ", 1)[0].rstrip(",;:—-") + "…"
    return sentence or None


def _tags(raw: Any) -> list[str]:
    items = (raw or {}).get("tag") if isinstance(raw, dict) else raw
    if isinstance(items, dict):
        items = [items]
    result: list[str] = []
    for item in items or []:
        name = str((item.get("name") if isinstance(item, dict) else item) or "").strip()
        if name and name.lower() not in _NOISE_TAGS and name.lower() not in (t.lower() for t in result):
            result.append(name)
    return result[:MAX_TAGS]


class ArtistFacts:
    def __init__(self, lastfm: LastFmClient | None, clock: Callable[[], float] = time.monotonic) -> None:
        self._lastfm = lastfm
        self._clock = clock
        self._cache: dict[tuple[str, str], tuple[float, dict[str, Any]]] = {}

    @property
    def available(self) -> bool:
        return self._lastfm is not None

    async def facts(self, artist: str, title: str | None = None) -> dict[str, Any]:
        """`{"fact": str | None, "tags": [...], "source": "lastfm" | None}`."""
        empty: dict[str, Any] = {"fact": None, "tags": [], "source": None}
        if self._lastfm is None or not artist.strip():
            return empty
        key = (artist.strip().lower(), (title or "").strip().lower())
        cached = self._cache.get(key)
        if cached and self._clock() - cached[0] < CACHE_TTL_S:
            return cached[1]
        try:
            result = await self._fetch(artist.strip(), (title or "").strip() or None)
        except LastFmError as e:
            log.info("Факт для «%s»: Last.fm недоступен (%s)", artist, e)
            return empty  # сбой не кэшируется — попробуем при следующем заходе
        if len(self._cache) >= CACHE_MAX:
            self._cache.pop(next(iter(self._cache)))
        self._cache[key] = (self._clock(), result)
        return result

    async def _fetch(self, artist: str, title: str | None) -> dict[str, Any]:
        assert self._lastfm is not None
        fact: str | None = None
        tags: list[str] = []
        if title:
            body = await self._lastfm.call("track.getInfo", artist=artist, track=title, autocorrect=1)
            track = (body or {}).get("track") or {}
            fact = first_sentence((track.get("wiki") or {}).get("summary"))
            tags = _tags(track.get("toptags"))
        if fact is None or not tags:
            body = await self._lastfm.call("artist.getInfo", artist=artist, autocorrect=1, lang="ru")
            info = (body or {}).get("artist") or {}
            fact = fact or first_sentence((info.get("bio") or {}).get("summary"))
            tags = tags or _tags(info.get("tags"))
        return {"fact": fact, "tags": tags, "source": "lastfm" if fact or tags else None}
