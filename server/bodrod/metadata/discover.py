"""Глобальный поиск: понять по запросу, ищут ли песню или исполнителя, и отметить то, что уже есть в библиотеке."""

from __future__ import annotations

import asyncio
import logging

from ..clients.navidrome import Credentials, NavidromeClient
from ..models import ArtistCandidate, DiscoverResponse, TrackCandidate
from ..text import normalize, similarity
from .providers import MetadataProvider, SearchHits

log = logging.getLogger(__name__)

TOP_TRACKS_LIMIT = 30
ARTIST_MATCH = 0.88
LIBRARY_MATCH = 0.85
LIBRARY_CHECK_LIMIT = 30


class DiscoverService:
    def __init__(self, providers: list[MetadataProvider], navidrome: NavidromeClient) -> None:
        self._providers = providers
        self._navidrome = navidrome

    @property
    def source_names(self) -> list[str]:
        return [p.name for p in self._providers]

    async def discover(self, query: str, creds: Credentials | None = None) -> DiscoverResponse:
        query = query.strip()
        for provider in self._providers:
            try:
                hits = await provider.search(query)
            except Exception as e:  # noqa: BLE001 — каталог недоступен: пробуем следующий
                log.warning("Каталог %s не ответил на «%s»: %s", provider.name, query, e)
                continue
            if hits.is_empty:
                continue
            response = await self._build(provider, query, hits)
            await self._mark_library(response, creds)
            return response
        return DiscoverResponse(intent="none")

    async def top_tracks(self, source: str, artist_id: str, creds: Credentials | None = None) -> list[TrackCandidate]:
        provider = next((p for p in self._providers if p.name == source), None)
        if provider is None:
            return []
        tracks = await provider.top_tracks(artist_id, TOP_TRACKS_LIMIT)
        await self._mark_tracks(tracks, creds)
        return tracks

    async def _build(self, provider: MetadataProvider, query: str, hits: SearchHits) -> DiscoverResponse:
        artist = pick_artist(query, hits)
        if artist is not None:
            try:
                top = await provider.top_tracks(artist.source_id, TOP_TRACKS_LIMIT)
            except Exception as e:  # noqa: BLE001
                log.warning("Не удалось получить популярные треки %s: %s", artist.name, e)
                top = [t for t in hits.tracks if similarity(t.artist, artist.name) >= ARTIST_MATCH]
            return DiscoverResponse(
                intent="artist",
                source=provider.name,
                artist=artist,
                top_tracks=top,
                tracks=hits.tracks,
                artists=hits.artists,
            )
        return DiscoverResponse(
            intent="track" if hits.tracks else "none",
            source=provider.name,
            tracks=rank_tracks(query, hits.tracks),
            artists=hits.artists,
        )

    async def _mark_library(self, response: DiscoverResponse, creds: Credentials | None) -> None:
        await self._mark_tracks(response.top_tracks + response.tracks, creds)

    async def _mark_tracks(self, tracks: list[TrackCandidate], creds: Credentials | None) -> None:
        seen: dict[str, TrackCandidate] = {}
        for t in tracks:
            seen.setdefault(f"{normalize(t.artist)}|{normalize(t.title)}", t)
        unique = list(seen.values())[:LIBRARY_CHECK_LIMIT]
        semaphore = asyncio.Semaphore(6)

        async def check(track: TrackCandidate) -> None:
            async with semaphore:
                try:
                    track.library_song_id = await self.find_in_library(track.artist, track.title, creds)
                except Exception as e:  # noqa: BLE001 — отметка «уже есть» необязательна
                    log.debug("Проверка библиотеки не удалась: %s", e)

        await asyncio.gather(*(check(t) for t in unique))
        found = {f"{normalize(t.artist)}|{normalize(t.title)}": t.library_song_id for t in unique}
        for t in tracks:
            t.library_song_id = found.get(f"{normalize(t.artist)}|{normalize(t.title)}")

    async def find_in_library(self, artist: str, title: str, creds: Credentials | None = None) -> str | None:
        return await find_in_library(self._navidrome, artist, title, creds)


async def find_in_library(
    navidrome: NavidromeClient, artist: str, title: str, creds: Credentials | None = None
) -> str | None:
    """id трека в Navidrome, если песня этого исполнителя уже есть в библиотеке."""
    songs = await navidrome.search_songs(normalize(title), count=30, creds=creds)
    main_artist = artist.split(",")[0]
    best: tuple[float, str] | None = None
    for song in songs:
        title_score = similarity(song.title, title)
        artist_score = max(similarity(song.artist, artist), similarity(song.artist, main_artist))
        if normalize(main_artist) and normalize(main_artist) in normalize(song.artist):
            artist_score = max(artist_score, 1.0)
        if title_score >= LIBRARY_MATCH and artist_score >= LIBRARY_MATCH:
            score = title_score + artist_score
            if best is None or score > best[0]:
                best = (score, song.id)
    return best[1] if best else None


def pick_artist(query: str, hits: SearchHits) -> ArtistCandidate | None:
    """Исполнитель, если запрос — это его имя (а не название песни)."""
    if not hits.artists:
        return None
    first = hits.artists[0]
    if normalize(first.name) == normalize(query):
        return first
    # Если есть песня с точно таким названием, ищут песню, даже когда имя исполнителя похоже.
    if any(normalize(t.title) == normalize(query) for t in hits.tracks[:5]):
        return None
    score = similarity(query, first.name)
    if score >= ARTIST_MATCH or (hits.best_is_artist and score >= 0.6):
        return first
    return None


def rank_tracks(query: str, tracks: list[TrackCandidate]) -> list[TrackCandidate]:
    """Точное совпадение названия — выше; в остальном порядок каталога (он учитывает популярность)."""
    q = normalize(query, strip_extras=False)

    def key(item: tuple[int, TrackCandidate]) -> tuple[int, int]:
        index, track = item
        variants = (track.title, f"{track.artist} {track.title}", f"{track.title} {track.artist}")
        exact = any(normalize(v, strip_extras=False) == q for v in variants)
        return (0 if exact else 1, index)

    return [t for _, t in sorted(enumerate(tracks), key=key)]
