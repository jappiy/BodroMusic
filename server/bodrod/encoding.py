"""Битая кодировка тегов: русский текст в Windows-1251, прочитанный как Latin-1 / CP1252.

«Ñåêðåò» → «Секрет», «Ìàøèíà Âðåìåíè» → «Машина Времени». Так выглядят ID3v1 и ID3v2 с флагом ISO-8859-1,
записанные старыми программами в русской Windows. Ломаются отдельные поля: альбом может быть нормальным,
а название и исполнитель — нет.

Правило восстановления (`repair`): в строке нет кириллицы; каждое слово с не-ASCII символами после перекодирования
`cp1252 → cp1251` (запасной вариант — `latin-1 → cp1251`) состоит только из кириллических букв (цифры и знаки
допустимы), а результат похож на русский текст (доля гласных, нет слов с «ъ»/«ь» в начале). Настоящие латинские
названия с диакритикой («Beyoncé», «Mötley Crüe», «Sigur Rós») не проходят: в их словах после перекодирования
латиница смешивается с кириллицей.

Строку, похожую на битую, но не восстановимую («????», смешанный мусор), автоматически не трогаем —
`unrecoverable` отправляет её на ручную проверку.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .db import Store, now_ms

FIELDS = ("title", "artist", "album", "albumartist", "genre", "composer")
"""Проверяемые теги (ключи «лёгкого» интерфейса mutagen: EasyID3, EasyMP4, Vorbis comments)."""

FIELD_NAMES = {
    "title": "название",
    "artist": "исполнитель",
    "album": "альбом",
    "albumartist": "исполнитель альбома",
    "genre": "жанр",
    "composer": "композитор",
}

_CYRILLIC = re.compile(r"[Ѐ-ӿ]")
_LATIN1_LETTERS = re.compile(r"[À-ÿ]")
_QUESTIONS = re.compile(r"\?{2,}")
_VOWELS = set("аеёиоуыэюяіїєАЕЁИОУЫЭЮЯІЇЄ")
_BAD_START = set("ъьЪЬ")
MIN_LETTERS = 2
"""Короче — не исправляем: слишком мало букв, чтобы отличить мусор от слова."""


def has_cyrillic(value: str) -> bool:
    return bool(_CYRILLIC.search(value))


def _recode(value: str) -> str | None:
    for codec in ("cp1252", "latin-1"):
        try:
            return value.encode(codec).decode("cp1251")
        except (UnicodeEncodeError, UnicodeDecodeError):
            continue
    return None


def _is_cyrillic_letter(ch: str) -> bool:
    return ch.isalpha() and bool(_CYRILLIC.match(ch))


def repair(value: str | None) -> str | None:
    """Исправленная строка или `None`, если строка не битая (или восстановить её надёжно нельзя)."""
    if not value or value.isascii() or has_cyrillic(value):
        return None
    fixed = _recode(value)
    if fixed is None or fixed == value:
        return None
    letters = 0
    vowels = 0
    for word, new in zip(value.split(" "), fixed.split(" "), strict=False):
        if word.isascii():
            continue  # «feat.», «2», латинское слово рядом с русским — как есть
        for ch in new:
            if not ch.isalpha():
                continue
            if not _is_cyrillic_letter(ch):
                return None  # «Beyoncй», «Mцtley»: латиница вперемешку с кириллицей — это не битая строка
            letters += 1
            vowels += ch in _VOWELS
        if new[:1] in _BAD_START:
            return None
    if letters < MIN_LETTERS:
        return None
    share = vowels / letters
    if letters >= 4 and not 0.15 <= share <= 0.8:
        return None
    return fixed


def suspicious(value: str | None) -> bool:
    """Похоже на битую кодировку: «????», символ замены или в основном буквы À–ÿ без кириллицы."""
    if not value or has_cyrillic(value):
        return False
    if "�" in value or _QUESTIONS.search(value):
        return True
    letters = [ch for ch in value if ch.isalpha()]
    high = sum(1 for ch in letters if _LATIN1_LETTERS.match(ch))
    return high >= 3 and high / max(1, len(letters)) >= 0.5


def unrecoverable(value: str | None) -> bool:
    """Битая, но автоматически не восстанавливается — на ручную проверку."""
    return suspicious(value) and repair(value) is None


def repair_fields(values: dict[str, list[str]]) -> tuple[dict[str, list[str]], list[str]]:
    """Исправления по полям (`{поле: [значения]}`) и список полей, которые надо проверить вручную."""
    fixed: dict[str, list[str]] = {}
    manual: list[str] = []
    for key in FIELDS:
        items = values.get(key) or []
        new = [repair(v) or v for v in items]
        if new != items:
            fixed[key] = new
        elif any(unrecoverable(v) for v in items):
            manual.append(key)
    return fixed, manual


# region Теги файла (mutagen)


def read_values(path: Path) -> dict[str, list[str]] | None:
    """Проверяемые теги файла. У MP3 без ID3v2 mutagen поднимает ID3v1. `None` — файл не читается."""
    try:
        import mutagen

        audio = mutagen.File(path, easy=True)
    except Exception:  # noqa: BLE001 — битый файл: им займётся шаг «Теги и обложки»
        return None
    if audio is None:
        return None
    tags = audio.tags or {}
    result: dict[str, list[str]] = {}
    for key in FIELDS:
        with contextlib.suppress(Exception):  # EasyMP4 и др. не знают части ключей
            values = [str(v) for v in (tags.get(key) or []) if str(v).strip()]
            if values:
                result[key] = values
    return result


def write_values(path: Path, values: dict[str, list[str]]) -> None:
    """Записать поля. MP3 — ID3v2.4 в UTF-8, ID3v1 удаляется; FLAC/OGG — Vorbis comments; M4A — атомы iTunes."""
    import mutagen
    from mutagen.mp3 import EasyMP3

    audio = mutagen.File(path, easy=True)
    if audio is None:
        raise ValueError(f"Неизвестный формат: {path.name}")
    if audio.tags is None:
        audio.add_tags()
    for key, items in values.items():
        if items:
            audio[key] = items
        else:
            with contextlib.suppress(KeyError):
                del audio[key]
    if isinstance(audio, EasyMP3):
        audio.save(v1=0, v2_version=4)
    else:
        audio.save()


# endregion

# region Журнал исправлений (откат)

SCHEMA = """
CREATE TABLE IF NOT EXISTS tag_encoding_fixes (
    id INTEGER PRIMARY KEY,
    run_id INTEGER,
    path TEXT NOT NULL,
    before TEXT NOT NULL,
    after TEXT NOT NULL,
    source TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    reverted_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tag_encoding_run ON tag_encoding_fixes(run_id, id);
"""


@dataclass
class EncodingFix:
    id: int
    run_id: int | None
    path: str
    before: dict[str, list[str]]
    after: dict[str, list[str]]
    source: str
    created_at: int
    reverted_at: int | None

    def changes(self) -> list[tuple[str, str, str]]:
        """(поле, было, стало) — для отчёта."""
        return [
            (key, "; ".join(self.before.get(key) or []), "; ".join(self.after.get(key) or []))
            for key in FIELDS
            if key in self.after
        ]


class EncodingJournal:
    """Исходные значения тегов до исправления: из журнала любое исправление можно откатить."""

    def __init__(self, store: Store) -> None:
        self._store = store
        store.executescript(SCHEMA)

    def add(
        self,
        run_id: int | None,
        path: str,
        before: dict[str, list[str]],
        after: dict[str, list[str]],
        source: str,
    ) -> int:
        return self._store.execute(
            "INSERT INTO tag_encoding_fixes (run_id, path, before, after, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                run_id,
                path,
                json.dumps(before, ensure_ascii=False),
                json.dumps(after, ensure_ascii=False),
                source,
                now_ms(),
            ),
        )

    def for_run(self, run_id: int) -> list[EncodingFix]:
        rows = self._store.query("SELECT * FROM tag_encoding_fixes WHERE run_id = ? ORDER BY id", (run_id,))
        return [_fix_from_row(r) for r in rows]

    def get(self, fix_id: int) -> EncodingFix | None:
        row = self._store.query_one("SELECT * FROM tag_encoding_fixes WHERE id = ?", (fix_id,))
        return _fix_from_row(row) if row else None

    def reverted_paths(self) -> set[str]:
        rows = self._store.query("SELECT DISTINCT path FROM tag_encoding_fixes WHERE reverted_at IS NOT NULL")
        return {r["path"] for r in rows}

    def mark_reverted(self, fix_id: int) -> None:
        self._store.execute("UPDATE tag_encoding_fixes SET reverted_at = ? WHERE id = ?", (now_ms(), fix_id))


def _fix_from_row(row: dict[str, Any]) -> EncodingFix:
    return EncodingFix(
        id=row["id"],
        run_id=row["run_id"],
        path=row["path"],
        before=json.loads(row["before"]),
        after=json.loads(row["after"]),
        source=row["source"],
        created_at=row["created_at"],
        reverted_at=row["reverted_at"],
    )


def revert(fix: EncodingFix) -> None:
    """Вернуть исходные значения исправленных полей (файл — по абсолютному пути из журнала)."""
    write_values(Path(fix.path), {key: fix.before.get(key) or [] for key in fix.after})


# endregion

# region AcoustID

ACOUSTID_TIMEOUT_S = 20.0


async def acoustid_lookup(path: Path, api_key: str) -> dict[str, str] | None:
    """Название / исполнитель / альбом по отпечатку звука (`fpcalc` + api.acoustid.org).

    `None` — нет `fpcalc`, ключа или уверенного совпадения (оценка ≥ 0,9).
    """
    fpcalc = shutil.which("fpcalc")
    if not fpcalc or not api_key:
        return None
    proc = await asyncio.create_subprocess_exec(
        fpcalc, "-json", str(path), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), ACOUSTID_TIMEOUT_S)
    except TimeoutError:
        proc.kill()
        return None
    try:
        fp = json.loads(out)
        params = {
            "client": api_key,
            "meta": "recordings releasegroups",
            "duration": str(int(fp["duration"])),
            "fingerprint": fp["fingerprint"],
        }
        import httpx

        async with httpx.AsyncClient(timeout=ACOUSTID_TIMEOUT_S) as client:
            response = await client.get("https://api.acoustid.org/v2/lookup", params=params)
            response.raise_for_status()
            data = response.json()
    except Exception:  # noqa: BLE001 — нет сети или ответ без совпадений
        return None
    for result in sorted(data.get("results") or [], key=lambda r: -float(r.get("score") or 0)):
        if float(result.get("score") or 0) < 0.9:
            break
        for recording in result.get("recordings") or []:
            title = recording.get("title")
            artists = recording.get("artists") or []
            if not title or not artists:
                continue
            groups = recording.get("releasegroups") or []
            return {
                "title": title,
                "artist": "".join(a.get("name", "") + (a.get("joinphrase") or "") for a in artists),
                "album": (groups[0].get("title") if groups else "") or "",
            }
    return None


# endregion
