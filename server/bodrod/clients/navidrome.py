"""Navidrome (Subsonic API): проверка учёток пользователей, поиск в библиотеке, запуск сканирования."""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass
from typing import Any

import httpx

CLIENT_NAME = "bodrod"
API_VERSION = "1.16.1"


class SubsonicError(Exception):
    def __init__(self, code: int, message: str | None) -> None:
        super().__init__(f"Subsonic error {code}: {message}")
        self.code = code


@dataclass(frozen=True)
class Credentials:
    """Token-авторизация Subsonic: `t = md5(password + s)`."""

    username: str
    token: str
    salt: str

    @classmethod
    def from_password(cls, username: str, password: str) -> Credentials:
        salt = secrets.token_hex(8)
        token = hashlib.md5((password + salt).encode()).hexdigest()  # noqa: S324 — так устроен Subsonic
        return cls(username, token, salt)


@dataclass(frozen=True)
class LibrarySong:
    id: str
    title: str
    artist: str
    album: str | None
    duration_s: int | None


class NavidromeClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, admin_user: str, admin_password: str) -> None:
        self._http = http
        self._base = base_url.rstrip("/")
        if self._base.endswith("/rest"):
            self._base = self._base[: -len("/rest")]
        self._admin_user = admin_user
        self._admin_password = admin_password

    def _admin(self) -> Credentials:
        return Credentials.from_password(self._admin_user, self._admin_password)

    async def _call(self, endpoint: str, creds: Credentials, **params: Any) -> dict[str, Any]:
        query = {
            "u": creds.username,
            "t": creds.token,
            "s": creds.salt,
            "v": API_VERSION,
            "c": CLIENT_NAME,
            "f": "json",
            **{k: v for k, v in params.items() if v is not None},
        }
        response = await self._http.get(f"{self._base}/rest/{endpoint}.view", params=query, timeout=20)
        response.raise_for_status()
        body = response.json().get("subsonic-response") or {}
        if body.get("status") != "ok":
            error = body.get("error") or {}
            raise SubsonicError(int(error.get("code", 0)), error.get("message"))
        return body

    async def check_user(self, creds: Credentials) -> bool:
        """`True` — учётные данные пользователя приняты Navidrome."""
        try:
            await self._call("ping", creds)
        except SubsonicError as e:
            if e.code in (40, 41, 44, 50):
                return False
            raise
        return True

    async def search_songs(self, query: str, count: int = 20, creds: Credentials | None = None) -> list[LibrarySong]:
        body = await self._call(
            "search3", creds or self._admin(), query=query, songCount=count, albumCount=0, artistCount=0
        )
        songs = (body.get("searchResult3") or {}).get("song") or []
        return [
            LibrarySong(
                id=str(s["id"]),
                title=s.get("title") or "",
                artist=s.get("artist") or "",
                album=s.get("album"),
                duration_s=s.get("duration"),
            )
            for s in songs
            if not s.get("isDir")
        ]

    async def star(self, creds: Credentials, song_ids: list[str]) -> None:
        """Лайк от имени пользователя (`star?id=…&id=…`)."""
        if song_ids:
            await self._call("star", creds, id=song_ids)

    async def start_scan(self) -> None:
        await self._call("startScan", self._admin())

    async def is_scanning(self) -> bool:
        body = await self._call("getScanStatus", self._admin())
        return bool((body.get("scanStatus") or {}).get("scanning"))
