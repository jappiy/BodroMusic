"""Чтение БД Navidrome (`navidrome.db`) только на чтение: дизлайки всех пользователей и пути к файлам.

Subsonic API отдаёт пользователю только его собственные оценки, поэтому списки «Не нравятся всем» и
«Не нравятся <пользователь>» строятся по таблице `annotation`. Дизлайк в BodroMusic — `rating = 1`.
Navidrome пишет в БД в режиме WAL, поэтому каждое чтение открывает короткое соединение `mode=ro`.
"""

from __future__ import annotations

import os
import sqlite3
from collections.abc import Iterator
from dataclasses import dataclass, field

DISLIKE_RATING = 1


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
        return (
            "SELECT mf.id, mf.title, mf.artist, mf.album, mf.album_id, mf.duration, mf.size, mf.suffix, "
            f"mf.bit_rate, mf.path, {library_path} AS library_path FROM media_file mf {join}"
        )

    def _not_missing(self, conn: sqlite3.Connection) -> str:
        return "mf.missing = 0" if "missing" in self._media_columns(conn) else "1 = 1"

    def users(self) -> list[NdUser]:
        with self._connect() as conn:
            rows = conn.execute("SELECT id, user_name, is_admin FROM user ORDER BY user_name").fetchall()
        return [NdUser(r["id"], r["user_name"], bool(r["is_admin"])) for r in rows]

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

    def iter_songs(self) -> Iterator[NdSong]:
        """Все треки библиотеки (для поиска заблокированных). Читается потоком, без загрузки в память целиком."""
        with self._connect() as conn:
            for r in conn.execute(f"{self._select(conn)} WHERE {self._not_missing(conn)}"):
                yield _song(r)


def _song(r: sqlite3.Row) -> NdSong:
    path = r["path"] or ""
    if not os.path.isabs(path) and r["library_path"]:
        path = os.path.join(r["library_path"], path)  # Navidrome 0.55+: путь относительно библиотеки
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
    )
