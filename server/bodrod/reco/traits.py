"""Признаки трека для режимов волны: то, что посчитал анализатор, плюс выведенное из него и из тегов.

Анализатор (`analyzer/`, Essentia) пишет: BPM, тональность и лад, громкость (LUFS, RMS), danceability, настроения
happy / sad / aggressive / relaxed, голос/инструментал, жанры discogs400 и (с 0.3.0, если есть модели) acoustic /
electronic. Здесь из них получаются признаки, на которые опираются режимы:

- `energy` 0..1 — танцевальность, агрессия, не-расслабленность, темп и громкость;
- `valence` 0..1 — позитивность: happy против sad;
- `acoustic` / `electronic` — модель, а без неё — по жанрам discogs (Electronic → электронное; Folk, Classical,
  Jazz, Blues, акустические стили → акустическое);
- `instrumental` — 1 − voice; «без слов» — от [INSTRUMENTAL_MIN] (один порог и для режима, и для языка `none`);
- `loudness` — EBU R128 (LUFS) от анализатора; у моно-файлов анализатор до 0.3.1 её не считал — тогда оценка по RMS
  (`rmsDb`, для музыки расходится с LUFS на 1–3 дБ), `loudness_estimated` = True;
- `camelot` — тональность в колесе Camelot («8A») для гармоничных соседей;
- `lang` — язык вокала. Модели языка в Essentia нет, поэтому определяется по тегу файла `language`, а без него —
  по письменности названия и исполнителя: кириллица → `ru`, только латиница → `en` (приблизительно: латиницей пишут
  и на других языках); инструментал → `none`.
"""

from __future__ import annotations

import re
from typing import Any

INSTRUMENTAL_MIN = 0.7
"""Порог «без слов» (1 − voice): с него трек попадает в режим «Без слов» и получает язык `none`."""
_CYRILLIC = re.compile(r"[А-Яа-яЁё]")
_LATIN = re.compile(r"[A-Za-z]")
_ELECTRONIC_GENRES = ("Electronic",)
_ACOUSTIC_GENRES = ("Folk, World, & Country", "Classical", "Jazz", "Blues")
_ACOUSTIC_STYLES = ("Acoustic", "Folk", "Singer/Songwriter", "Chanson", "Bossa Nova", "Country", "Bluegrass")

"""Колесо Camelot: (тональность, лад) → код. Энгармонизмы приведены к диезам."""
_CAMELOT_MINOR = {"G#": "1A", "D#": "2A", "A#": "3A", "F": "4A", "C": "5A", "G": "6A", "D": "7A", "A": "8A",
                  "E": "9A", "B": "10A", "F#": "11A", "C#": "12A"}  # fmt: skip
_CAMELOT_MAJOR = {"B": "1B", "F#": "2B", "C#": "3B", "G#": "4B", "D#": "5B", "A#": "6B", "F": "7B", "C": "8B",
                  "G": "9B", "D": "10B", "A": "11B", "E": "12B"}  # fmt: skip
_FLATS = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}


def camelot(key: str | None, scale: str | None) -> str | None:
    if not key or not scale:
        return None
    note = _FLATS.get(key, key)
    return (_CAMELOT_MINOR if scale.lower().startswith("min") else _CAMELOT_MAJOR).get(note)


def camelot_compatible(a: str | None, b: str | None) -> bool:
    """Совместимые тональности: та же, соседняя по кругу (±1) с тем же ладом, или параллельная (8A ↔ 8B)."""
    if not a or not b:
        return True  # неизвестная тональность не мешает
    na, la, nb, lb = int(a[:-1]), a[-1], int(b[:-1]), b[-1]
    if la == lb:
        return (na - nb) % 12 in (0, 1, 11)
    return na == nb


def _genre_share(genres: list[Any], prefixes: tuple[str, ...], styles: tuple[str, ...] = ()) -> float:
    share = 0.0
    for item in genres or []:
        if not isinstance(item, list | tuple) or len(item) < 2:
            continue
        label, prob = str(item[0]), float(item[1] or 0)
        parent, _, style = label.partition("---")
        if parent in prefixes or any(s.lower() in style.lower() for s in styles):
            share += prob
    return min(1.0, share * 1.6)


def energy(features: dict[str, Any]) -> float | None:
    moods = features.get("moods") or {}
    dance = features.get("danceability")
    if dance is None and not moods:
        return None
    bpm = features.get("bpm") or 110.0
    tempo = min(1.0, max(0.0, (float(bpm) - 70.0) / 100.0))
    parts = [float(dance if dance is not None else 0.5), float(moods.get("aggressive", 0.3)),
             1.0 - float(moods.get("relaxed", 0.5)), tempo]  # fmt: skip
    loudness = features.get("loudness") if features.get("loudness") is not None else features.get("rmsDb")
    if loudness is not None:
        parts.append(min(1.0, max(0.0, (float(loudness) + 20.0) / 14.0)))  # −20 LUFS → 0, −6 → 1
    return round(sum(parts) / len(parts), 4)


def language(title: str, artist: str, album: str | None, tag: str | None, instrumental: float | None) -> str | None:
    if tag:
        value = tag.strip().lower()[:3]
        return {"rus": "ru", "ru": "ru", "eng": "en", "en": "en"}.get(value, value[:2] or None)
    if instrumental is not None and instrumental >= INSTRUMENTAL_MIN:
        return "none"
    text = f"{title} {artist} {album or ''}"
    if _CYRILLIC.search(text):
        return "ru"
    if _LATIN.search(text):
        return "en"
    return None


def traits(features: dict[str, Any] | None, title: str, artist: str, album: str | None = None,
           language_tag: str | None = None) -> dict[str, Any]:  # fmt: skip
    """Все признаки для режимов; у непроанализированного трека — только язык по названию."""
    f = features or {}
    moods = f.get("moods") or {}
    happy, sad = moods.get("happy"), moods.get("sad")
    voice = f.get("voice")
    instrumental = round(1.0 - float(voice), 4) if voice is not None else None
    genres = f.get("genres") or []
    electronic = f.get("electronic")
    acoustic = f.get("acoustic")
    result: dict[str, Any] = {
        "analyzed": bool(f),
        "bpm": f.get("bpm"),
        "energy": f.get("energy") if f.get("energy") is not None else energy(f),
        "valence": round((float(happy) + 1.0 - float(sad)) / 2, 4) if happy is not None and sad is not None else None,
        "happy": happy,
        "sad": sad,
        "aggressive": moods.get("aggressive"),
        "relaxed": moods.get("relaxed"),
        "danceability": f.get("danceability"),
        "instrumental": instrumental,
        "electronic": float(electronic)
        if electronic is not None
        else (_genre_share(genres, _ELECTRONIC_GENRES) if genres else None),
        "acoustic": float(acoustic)
        if acoustic is not None
        else (_genre_share(genres, _ACOUSTIC_GENRES, _ACOUSTIC_STYLES) if genres else None),
        "loudness": f.get("loudness") if f.get("loudness") is not None else f.get("rmsDb"),
        "loudness_estimated": f.get("loudness") is None and f.get("rmsDb") is not None,
        "camelot": camelot(f.get("key"), f.get("scale")),
        "lang": language(title, artist, album, language_tag, instrumental),
    }
    return result
