"""Чтение БД Navidrome (`navidrome.db`) только на чтение: дизлайки всех пользователей и пути к файлам.

Subsonic API отдаёт пользователю только его собственные оценки, поэтому списки «Не нравятся всем» и
«Не нравятся <пользователь>» строятся по таблице `annotation`. Дизлайк в BodroMusic — `rating = 1`.
Navidrome пишет в БД в режиме WAL, поэтому каждое чтение открывает короткое соединение `mode=ro`.
"""

from __future__ import annotations

import json
import posixpath
import sqlite3
from collections.abc import Iterator
from dataclasses import dataclass, field
from typing import Any

DISLIKE_RATING = 1
_OPTIONAL_COLUMNS = (
    "album_artist", "disc_number", "track_number", "has_cover_art", "artist_id", "album_artist_id", "tags",
    "mbz_recording_id", "mbz_album_id", "genre", "year", "created_at", "lyrics",
)  # fmt: skip


@dataclass(frozen=True)
class NdUser:
    id: str
    username: str
    is_admin: bool


@dataclass
class NdSong:
    id: str
    title: str
    artist: str
    album: str | None
    album_id: str | None
    duration_s: float | None
    size: int | None
    suffix: str | None
    bit_rate: int | None
    """Абсолютный путь так, как его видит Navidrome."""
    path: str
    disliked_by: list[str] = field(default_factory=list)
    album_artist: str | None = None
    disc_number: int | None = None
    track_number: int | None = None
    """Есть встроенная обложка (по данным сканера Navidrome)."""
    has_cover_art: bool | None = None
    artist_id: str | None = None
    album_artist_id: str | None = None
    """ISRC из тегов (Navidrome 0.55+, колонка `tags`)."""
    isrc: str | None = None
    """MusicBrainz Recording ID (теги beets/Picard) — стабильный ключ анализа звучания."""
    mbid: str | None = None
    """MusicBrainz Release ID альбома (обложка папки Lidarr из Cover Art Archive)."""
    album_mbid: str | None = None
    genre: str | None = None
    year: int | None = None
    """Когда трек появился в библиотеке (строка даты Navidrome) — режим «Новое», приоритет анализа новых."""
    created_at: str | None = None
    """Текст в тегах файла (колонка `lyrics`, Navidrome 0.54+): "synced", "plain" или `None` — нет."""
    embedded_lyrics: str | None = None


@dataclass(frozen=True)
class NdAnnotation:
    username: str
    item_id: str
    starred: bool
    rating: int
    play_count: int
    """Когда последний раз слушал (строка даты из БД Navidrome)."""
    play_date: str | None = None
    starred_at: str | None = None


@dataclass(frozen=True)
class NdPlaylist:
    id: str
    name: str
    owner: str | None
    public: bool
    song_count: int
    """Файл, из которого Navidrome импортировал плейлист (`.m3u`, `.nsp`); `None` — создан через API или веб."""
    path: str | None
    """Умный плейлист (правила `.nsp`)."""
    smart: bool
    created_at: str | None = None


class NavidromeDb:
    def __init__(self, path: str) -> None:
        self._path = path
        self._columns: set[str] | None = None

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(f"file:{self._path}?mode=ro", uri=True, timeout=10, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _media_columns(self, conn: sqlite3.Connection) -> set[str]:
        if self._columns is None:
            self._columns = {r["name"] for r in conn.execute("PRAGMA table_info(media_file)")}
        return self._columns

    def _select(self, conn: sqlite3.Connection) -> str:
        cols = self._media_columns(conn)
        has_library = "library_id" in cols
        library_path = "l.path" if has_library else "NULL"
        join = "LEFT JOIN library l ON l.id = mf.library_id" if has_library else ""
        optional = ", ".join(f"mf.{c}" if c in cols else f"NULL AS {c}" for c in _OPTIONAL_COLUMNS)
        return (
            "SELECT mf.id, mf.title, mf.artist, mf.album, mf.album_id, mf.duration, mf.size, mf.suffix, "
            f"mf.bit_rate, mf.path, {optional}, {library_path} AS library_path FROM media_file mf {join}"
        )

    def _not_missing(self, conn: sqlite3.Connection) -> str:
        return "mf.missing = 0" if "missing" in self._media_columns(conn) else "1 = 1"

    def cover_stats(self) -> dict[str, Any]:
        """Сколько альбомов, треков и исполнителей без картинки (аудит обложек, `GET /api/v1/library/covers`).

        Альбом без обложки — ни картинки в его папках (`folder.image_files`, Navidrome 0.55+; раньше —
        `album.image_files`), ни вшитой (`embed_art_path`). Треки — из таких альбомов без своей вшитой обложки.
        По верхней папке (`library`, `_discovery`, …) — где их больше всего.
        """
        with self._connect() as conn:
            album_cols = {r["name"] for r in conn.execute("PRAGMA table_info(album)")}
            tables = {r["name"] for r in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
            missing_album = "a.missing = 0" if "missing" in album_cols else "1 = 1"
            folder_ids = "a.folder_ids" if "folder_ids" in album_cols else "NULL"
            image_files = "a.image_files" if "image_files" in album_cols else "NULL"
            albums = conn.execute(
                f"SELECT a.id, a.embed_art_path, {folder_ids} AS folder_ids, {image_files} AS image_files "
                f"FROM album a WHERE {missing_album}"
            ).fetchall()
            folder_images: dict[str, bool] = {}
            if "folder" in tables:
                for r in conn.execute("SELECT id, image_files FROM folder"):
                    folder_images[r["id"]] = (r["image_files"] or "") not in ("", "[]")
            without: set[str] = set()
            for a in albums:
                has = bool(a["embed_art_path"]) or (a["image_files"] or "") not in ("", "[]")
                if not has and a["folder_ids"]:
                    try:
                        ids = json.loads(a["folder_ids"])
                    except ValueError:
                        ids = []
                    has = any(folder_images.get(i) for i in ids)
                if not has:
                    without.add(a["id"])
            nm = self._not_missing(conn)
            songs_total = conn.execute(f"SELECT COUNT(*) FROM media_file mf WHERE {nm}").fetchone()[0]
            by_folder: dict[str, int] = {}
            songs_without = 0
            for r in conn.execute(f"SELECT mf.album_id, mf.path, mf.has_cover_art FROM media_file mf WHERE {nm}"):
                if r["album_id"] in without and not r["has_cover_art"]:
                    songs_without += 1
                    top = (r["path"] or "").replace("\\", "/").split("/")[0] or "?"
                    by_folder[top] = by_folder.get(top, 0) + 1
            artist_cols = {r["name"] for r in conn.execute("PRAGMA table_info(artist)")}
            missing_artist = "missing = 0" if "missing" in artist_cols else "1 = 1"
            artists, artists_without = conn.execute(
                "SELECT COUNT(*), SUM(CASE WHEN COALESCE(small_image_url, '') = '' THEN 1 ELSE 0 END) "
                f"FROM artist WHERE {missing_artist}"
            ).fetchone()
        return {
            "albums": len(albums),
            "albumsWithoutCover": len(without),
            "songs": songs_total,
            "songsWithoutCover": songs_without,
            "artists": artists or 0,
            "artistsWithoutImage": artists_without or 0,
            "byFolder": dict(sorted(by_folder.items(), key=lambda kv: -kv[1])),
        }

    def users(self) -> list[NdUser]:
        with self._connect() as conn:
            rows = conn.execute("SELECT id, user_name, is_admin FROM user ORDER BY user_name").fetchall()
        return [NdUser(r["id"], r["user_name"], bool(r["is_admin"])) for r in rows]

    def playlists(self) -> list[NdPlaylist]:
        """Все плейлисты сервера с владельцем — для отчёта администратору (Subsonic API показывает не всё)."""
        with self._connect() as conn:
            cols = {r["name"] for r in conn.execute("PRAGMA table_info(playlist)")}
            if not cols:
                return []
            opt = {c: (f"p.{c}" if c in cols else "NULL") for c in ("path", "rules", "song_count", "created_at")}
            rows = conn.execute(
                f"SELECT p.id, p.name, p.public, {opt['path']} AS path, {opt['rules']} AS rules, "
                f"{opt['song_count']} AS song_count, {opt['created_at']} AS created_at, u.user_name AS owner "
                f"FROM playlist p LEFT JOIN user u ON u.id = p.owner_id ORDER BY u.user_name, p.name"
            ).fetchall()
        return [
            NdPlaylist(
                id=r["id"], name=r["name"] or "", owner=r["owner"], public=bool(r["public"]),
                song_count=int(r["song_count"] or 0), path=r["path"] or None,
                smart=bool(r["rules"] and str(r["rules"]).strip() not in ("", "null", "{}")),
                created_at=str(r["created_at"]) if r["created_at"] is not None else None,
            )
            for r in rows
        ]  # fmt: skip

    def disliked(self, user_ids: list[str], require_all: bool) -> list[NdSong]:
        """Треки с дизлайком хотя бы одного (`require_all=False`) или всех перечисленных пользователей."""
        if not user_ids:
            return []
        marks = ",".join("?" for _ in user_ids)
        with self._connect() as conn:
            disliked_by: dict[str, list[str]] = {}
            for r in conn.execute(
                f"SELECT a.item_id, u.user_name FROM annotation a JOIN user u ON u.id = a.user_id "
                f"WHERE a.item_type = 'media_file' AND a.rating = ? AND a.user_id IN ({marks})",
                (DISLIKE_RATING, *user_ids),
            ):
                disliked_by.setdefault(r["item_id"], []).append(r["user_name"])
            wanted = [i for i, users in disliked_by.items() if not require_all or len(users) == len(user_ids)]
            songs = self._by_ids(conn, wanted)
        for song in songs:
            song.disliked_by = sorted(disliked_by.get(song.id, []))
        return sorted(songs, key=lambda s: (s.artist.lower(), s.title.lower()))

    def songs(self, ids: list[str]) -> list[NdSong]:
        with self._connect() as conn:
            return self._by_ids(conn, ids)

    def _by_ids(self, conn: sqlite3.Connection, ids: list[str]) -> list[NdSong]:
        result: list[NdSong] = []
        for start in range(0, len(ids), 500):  # лимит параметров SQLite
            chunk = ids[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            rows = conn.execute(
                f"{self._select(conn)} WHERE mf.id IN ({marks}) AND {self._not_missing(conn)}", chunk
            ).fetchall()
            result.extend(_song(r) for r in rows)
        return result

    def album_songs(self, album_ids: list[str]) -> list[NdSong]:
        """Все треки альбомов (место трека в альбоме, «самый энергичный трек альбома»)."""
        result: list[NdSong] = []
        with self._connect() as conn:
            for start in range(0, len(album_ids), 500):
                chunk = album_ids[start : start + 500]
                marks = ",".join("?" for _ in chunk)
                rows = conn.execute(
                    f"{self._select(conn)} WHERE mf.album_id IN ({marks}) AND {self._not_missing(conn)}", chunk
                ).fetchall()
                result.extend(_song(r) for r in rows)
        return result

    def present_ids(self, ids: list[str]) -> set[str]:
        """Какие из треков есть в библиотеке (не удалены и не помечены сканером как пропавшие)."""
        with self._connect() as conn:
            return {s.id for s in self._by_ids(conn, ids)}

    def song_ids_by_paths(self, paths: list[str]) -> dict[str, str]:
        """Путь (как его видит Navidrome) → id трека запросом по индексу пути (без обхода всей библиотеки).

        Navidrome 0.55+ хранит путь относительно папки библиотеки — ищем и абсолютный, и относительный вариант.
        """
        if not paths:
            return {}
        with self._connect() as conn:
            roots: list[str] = []
            if "library_id" in self._media_columns(conn):
                roots = [r["path"].rstrip("/") + "/" for r in conn.execute("SELECT path FROM library") if r["path"]]
            variants: dict[str, str] = {}
            for path in paths:
                variants[path] = path
                for root in roots:
                    if path.startswith(root):
                        variants[path[len(root) :]] = path
            result: dict[str, str] = {}
            keys = list(variants)
            for start in range(0, len(keys), 500):
                chunk = keys[start : start + 500]
                marks = ",".join("?" for _ in chunk)
                rows = conn.execute(
                    f"{self._select(conn)} WHERE mf.path IN ({marks}) AND {self._not_missing(conn)}", chunk
                ).fetchall()
                for r in rows:
                    result[variants[r["path"]]] = r["id"]
            return result

    def mbids_by_paths(self, paths: list[str]) -> dict[str, str]:
        """Путь (как его видит Navidrome) → MBID записи, включая пропавшие файлы (`missing`): по ним старая запись
        анализа находит трек, переехавший по новому пути."""
        if not paths:
            return {}
        with self._connect() as conn:
            if "mbz_recording_id" not in self._media_columns(conn):
                return {}
            roots: list[str] = []
            if "library_id" in self._media_columns(conn):
                roots = [r["path"].rstrip("/") + "/" for r in conn.execute("SELECT path FROM library") if r["path"]]
            variants: dict[str, str] = {}
            for path in paths:
                variants[path] = path
                for root in roots:
                    if path.startswith(root):
                        variants[path[len(root) :]] = path
            result: dict[str, str] = {}
            keys = list(variants)
            for start in range(0, len(keys), 500):
                chunk = keys[start : start + 500]
                marks = ",".join("?" for _ in chunk)
                for r in conn.execute(
                    f"SELECT path, mbz_recording_id FROM media_file WHERE path IN ({marks})", chunk
                ).fetchall():
                    if r["mbz_recording_id"]:
                        result[variants[r["path"]]] = r["mbz_recording_id"]
            return result

    def ids_by_path(self, paths: list[str]) -> dict[str, str]:
        """Путь (как его видит Navidrome) → id трека. Нужно, чтобы найти перенесённый файл после пересканирования."""
        wanted = set(paths)
        return {s.path: s.id for s in self.iter_songs() if s.path in wanted}

    def annotation_counts(self) -> dict[str, int]:
        """id трека → сколько у него отметок пользователей (лайки, оценки, прослушивания)."""
        with self._connect() as conn:
            cols = {r["name"] for r in conn.execute("PRAGMA table_info(annotation)")}
            parts = [c for c in ("starred", "rating", "play_count") if c in cols]
            if not parts:
                return {}
            score = " + ".join(f"(CASE WHEN COALESCE({c}, 0) > 0 THEN 1 ELSE 0 END)" for c in parts)
            rows = conn.execute(
                f"SELECT item_id, SUM({score}) AS n FROM annotation WHERE item_type = 'media_file' GROUP BY item_id"
            ).fetchall()
        return {r["item_id"]: int(r["n"] or 0) for r in rows if r["n"]}

    def annotations(self, item_ids: list[str]) -> list[NdAnnotation]:
        """Отметки пользователей на треках: лайк, оценка, прослушивания (для треков пула)."""
        result: list[NdAnnotation] = []
        if not item_ids:
            return result
        with self._connect() as conn:
            cols = {r["name"] for r in conn.execute("PRAGMA table_info(annotation)")}
            play_date = "a.play_date" if "play_date" in cols else "NULL"
            for start in range(0, len(item_ids), 500):
                chunk = item_ids[start : start + 500]
                marks = ",".join("?" for _ in chunk)
                rows = conn.execute(
                    f"SELECT u.user_name, a.item_id, COALESCE(a.starred, 0) AS starred, "
                    f"COALESCE(a.rating, 0) AS rating, "
                    f"COALESCE(a.play_count, 0) AS play_count, {play_date} AS play_date FROM annotation a "
                    f"JOIN user u ON u.id = a.user_id WHERE a.item_type = 'media_file' AND a.item_id IN ({marks})",
                    chunk,
                ).fetchall()
                result.extend(
                    NdAnnotation(r["user_name"], r["item_id"], bool(r["starred"]), int(r["rating"]),
                                 int(r["play_count"]), r["play_date"])
                    for r in rows
                )  # fmt: skip
        return result

    def user_annotations(self, username: str) -> list[NdAnnotation]:
        """Все отметки пользователя на треках: лайки, оценки, прослушивания (семена рекомендаций)."""
        with self._connect() as conn:
            cols = {r["name"] for r in conn.execute("PRAGMA table_info(annotation)")}
            play_date = "a.play_date" if "play_date" in cols else "NULL"
            starred_at = "a.starred_at" if "starred_at" in cols else "NULL"
            rows = conn.execute(
                f"SELECT u.user_name, a.item_id, COALESCE(a.starred, 0) AS starred, "
                f"COALESCE(a.rating, 0) AS rating, "
                f"COALESCE(a.play_count, 0) AS play_count, {play_date} AS play_date, {starred_at} AS starred_at "
                f"FROM annotation a JOIN user u ON u.id = a.user_id "
                f"WHERE a.item_type = 'media_file' AND u.user_name = ?",
                (username,),
            ).fetchall()
        return [
            NdAnnotation(r["user_name"], r["item_id"], bool(r["starred"]), int(r["rating"]), int(r["play_count"]),
                         r["play_date"], r["starred_at"])
            for r in rows
        ]  # fmt: skip

    def iter_songs(self) -> Iterator[NdSong]:
        """Все треки библиотеки (для поиска заблокированных). Читается потоком, без загрузки в память целиком."""
        with self._connect() as conn:
            for r in conn.execute(f"{self._select(conn)} WHERE {self._not_missing(conn)}"):
                yield _song(r)


def _song(r: sqlite3.Row) -> NdSong:
    path = r["path"] or ""
    if not posixpath.isabs(path) and r["library_path"]:
        path = posixpath.join(r["library_path"], path)  # Navidrome 0.55+: путь относительно библиотеки
    return NdSong(
        id=r["id"],
        title=r["title"] or "",
        artist=r["artist"] or "",
        album=r["album"],
        album_id=r["album_id"],
        duration_s=r["duration"],
        size=r["size"],
        suffix=r["suffix"],
        bit_rate=r["bit_rate"],
        path=path,
        album_artist=r["album_artist"] or None,
        disc_number=r["disc_number"],
        track_number=r["track_number"],
        has_cover_art=bool(r["has_cover_art"]) if r["has_cover_art"] is not None else None,
        artist_id=r["artist_id"] or None,
        album_artist_id=r["album_artist_id"] or None,
        isrc=_isrc(r["tags"]),
        mbid=r["mbz_recording_id"] or None,
        album_mbid=r["mbz_album_id"] or None,
        genre=r["genre"] or None,
        year=int(r["year"]) if r["year"] else None,
        created_at=str(r["created_at"]) if r["created_at"] else None,
        embedded_lyrics=_lyrics_kind(r["lyrics"]),
    )


def _lyrics_kind(raw: str | None) -> str | None:
    """`[{"lang": "…", "line": [{"start": 0, "value": "…"}], "synced": true}]` → "synced" / "plain" / `None`."""
    if not raw or raw in ("[]", "null"):
        return None
    try:
        items = json.loads(raw)
    except ValueError:
        return "plain" if raw.strip() else None  # Navidrome до 0.54 хранил текст строкой
    if not isinstance(items, list):
        return None
    kinds = [bool(i.get("synced")) for i in items if isinstance(i, dict) and i.get("line")]
    if not kinds:
        return None
    return "synced" if any(kinds) else "plain"


def _isrc(raw: str | None) -> str | None:
    """ISRC из JSON тегов Navidrome: `{"isrc": ["…"]}` или `{"isrc": [{"value": "…"}]}`."""
    if not raw or "isrc" not in raw.lower():
        return None
    try:
        tags = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(tags, dict):
        return None
    values = next((v for k, v in tags.items() if k.lower() == "isrc"), None)
    for value in values if isinstance(values, list) else [values]:
        text = value.get("value") if isinstance(value, dict) else value
        if isinstance(text, str) and text.strip():
            return text.strip().upper()
    return None
