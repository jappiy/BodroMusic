"""Выбор файла в результатах Soulseek: тот же исполнитель и песня, подходящая длительность, качество, быстрый пир."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .clients.slskd import SlskdFile, SlskdResponse
from .text import contains_all, normalize, to_latin, tokens, translit

# Версии, которые не нужны, если пользователь их явно не искал.
UNWANTED = (
    "remix",
    "rmx",
    "live",
    "karaoke",
    "instrumental",
    "acapella",
    "a capella",
    "cover",
    "minus",
    "минус",
    "ремикс",
    "караоке",
    "инструментал",
    "concert",
    "концерт",
    "sped up",
    "slowed",
    "nightcore",
    "edit",
)
LOSSLESS = {"flac", "alac", "wav"}
DURATION_TOLERANCE_S = 6
DURATION_REJECT_S = 20


@dataclass(frozen=True)
class Candidate:
    username: str
    filename: str
    size: int
    score: float

    def to_state(self) -> dict[str, object]:
        return {"username": self.username, "filename": self.filename, "size": self.size, "score": round(self.score, 2)}


def _basename(path: str) -> str:
    return path.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0]


def score_file(
    file: SlskdFile,
    peer: SlskdResponse,
    *,
    artist: str,
    title: str,
    duration_s: int | None,
    allowed_formats: tuple[str, ...],
    min_bitrate: int,
) -> float | None:
    """Оценка файла или `None`, если он точно не подходит."""
    if not file.extension or file.extension not in allowed_formats:
        return None
    # Сравнение в латинице: файл «Kirkorov - Luiza» подходит к заявке «Киркоров — Луиза».
    name = translit(_basename(file.filename))
    path = to_latin(normalize(file.filename.replace("\\", " ").replace("/", " "), strip_extras=False))
    title_tokens = [translit(t) for t in tokens(title)]
    if not title_tokens or not contains_all(name, title_tokens):
        return None
    artist_names = [a for a in (translit(x) for x in artist.split(",")) if a]
    if not any(contains_all(path, a.split(" ")) for a in artist_names):
        return None
    wanted_title = normalize(title, strip_extras=False)
    full_name = normalize(_basename(file.filename), strip_extras=False)
    for word in UNWANTED:
        if f" {word} " in f" {full_name} " and f" {word} " not in f" {wanted_title} ":
            return None

    score = 100.0
    # Лишние слова в имени файла — возможно, другая версия песни.
    extra = len(set(name.split(" ")) - set(title_tokens) - {t for a in artist_names for t in a.split(" ")})
    score -= min(extra, 6) * 2

    if duration_s and file.length_s:
        diff = abs(duration_s - file.length_s)
        if diff > DURATION_REJECT_S:
            return None
        score += 20 if diff <= DURATION_TOLERANCE_S else 5
    elif duration_s:
        score -= 5

    if file.extension in LOSSLESS:
        score += 25
    elif file.bit_rate:
        if file.bit_rate < min_bitrate:
            return None
        score += 20 if file.bit_rate >= 320 else 10 if file.bit_rate >= 256 else 0

    score += 15 if peer.has_free_slot else 0
    score -= min(peer.queue_length, 50) * 0.6
    if peer.upload_speed > 0:
        score += min(10.0, math.log2(peer.upload_speed / 50_000 + 1) * 3)
    return score


def rank_candidates(
    responses: list[SlskdResponse],
    *,
    artist: str,
    title: str,
    duration_s: int | None,
    allowed_formats: tuple[str, ...],
    min_bitrate: int,
    limit: int,
) -> list[Candidate]:
    """Лучшие файлы, не больше одного на пира (если первый не отдастся, следующий берём у другого)."""
    best_per_peer: dict[str, Candidate] = {}
    for peer in responses:
        for f in peer.files:
            score = score_file(
                f,
                peer,
                artist=artist,
                title=title,
                duration_s=duration_s,
                allowed_formats=allowed_formats,
                min_bitrate=min_bitrate,
            )
            if score is None:
                continue
            current = best_per_peer.get(peer.username)
            if current is None or score > current.score:
                best_per_peer[peer.username] = Candidate(peer.username, f.filename, f.size, score)
    return sorted(best_per_peer.values(), key=lambda c: c.score, reverse=True)[:limit]
