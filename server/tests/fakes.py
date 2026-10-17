"""Поддельные Navidrome / slskd / Lidarr / Яндекс / Deezer поверх httpx.MockTransport."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any

import httpx

NAVIDROME = "http://navidrome:4533"
SLSKD = "http://slskd:5030"
LIDARR = "http://lidarr:8686"

USERS = {"admin": "adminpass", "vasya": "secret"}


def subsonic_ok(**payload: Any) -> httpx.Response:
    return httpx.Response(200, json={"subsonic-response": {"status": "ok", "version": "1.16.1", **payload}})


def subsonic_error(code: int, message: str) -> httpx.Response:
    return httpx.Response(
        200, json={"subsonic-response": {"status": "failed", "error": {"code": code, "message": message}}}
    )


def yandex_track(track_id: int, title: str, artist: str, duration_ms: int = 200_000) -> dict[str, Any]:
    return {
        "id": track_id,
        "title": title,
        "durationMs": duration_ms,
        "artists": [{"id": 1, "name": artist}],
        "albums": [{"id": 10, "title": "Альбом"}],
        "coverUri": "avatars.yandex.net/get-music-content/abc/%%",
    }


@dataclass
class FakeWorld:
    library: list[dict[str, Any]] = field(default_factory=list)
    """Файлы, которые «появятся» в библиотеке после скана Navidrome."""
    pending_library: list[dict[str, Any]] = field(default_factory=list)
    scans: int = 0
    yandex_search: dict[str, Any] | None = None
    yandex_fails: bool = False
    yandex_artist_tracks: list[dict[str, Any]] = field(default_factory=list)
    deezer_tracks: list[dict[str, Any]] = field(default_factory=list)
    deezer_artists: list[dict[str, Any]] = field(default_factory=list)
    slskd_responses: list[dict[str, Any]] = field(default_factory=list)
    """Ответы по тексту запроса (если задано — вместо `slskd_responses`)."""
    slskd_responses_by_text: dict[str, list[dict[str, Any]]] | None = None
    slskd_searches: dict[str, dict[str, Any]] = field(default_factory=dict)
    """username → {filename: state}."""
    slskd_transfers: dict[str, dict[str, dict[str, Any]]] = field(default_factory=dict)
    slskd_reject_users: set[str] = field(default_factory=set)
    lidarr_artists: list[dict[str, Any]] = field(default_factory=list)
    lidarr_queue: int = 0
    lidarr_commands: list[dict[str, Any]] = field(default_factory=list)
    lidarr_removed_from_queue: list[int] = field(default_factory=list)
    lidarr_updates: list[dict[str, Any]] = field(default_factory=list)
    requests: list[httpx.Request] = field(default_factory=list)

    def client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=httpx.MockTransport(self.handle))

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        url = request.url
        base = f"{url.scheme}://{url.host}" + (f":{url.port}" if url.port else "")
        if base == NAVIDROME:
            return self._navidrome(request)
        if base == SLSKD:
            return self._slskd(request)
        if base == LIDARR:
            return self._lidarr(request)
        if url.host == "api.music.yandex.net":
            return self._yandex(request)
        if url.host == "api.deezer.com":
            return self._deezer(request)
        return httpx.Response(404)

    # region Navidrome

    def _navidrome(self, request: httpx.Request) -> httpx.Response:
        p = request.url.params
        password = USERS.get(p.get("u", ""))
        if password is None or hashlib.md5((password + p.get("s", "")).encode()).hexdigest() != p.get("t"):
            return subsonic_error(40, "Wrong username or password")
        endpoint = request.url.path.rsplit("/", 1)[-1]
        if endpoint == "ping.view":
            return subsonic_ok()
        if endpoint == "search3.view":
            words = p.get("query", "").lower().split()
            songs = [
                s
                for s in self.library
                if all(w in f"{s['title']} {s['artist']} {s.get('album', '')}".lower() for w in words)
            ]
            return subsonic_ok(searchResult3={"song": songs})
        if endpoint == "startScan.view":
            self.scans += 1
            self.library.extend(self.pending_library)
            self.pending_library.clear()
            return subsonic_ok(scanStatus={"scanning": True})
        if endpoint == "getScanStatus.view":
            return subsonic_ok(scanStatus={"scanning": False})
        return subsonic_error(70, "not found")

    # endregion

    # region slskd

    def _slskd(self, request: httpx.Request) -> httpx.Response:
        if request.headers.get("X-API-Key") != "slskd-key":
            return httpx.Response(401)
        path = request.url.path.removeprefix("/api/v0")
        if request.method == "POST" and path == "/searches":
            body = json.loads(request.content)
            self.slskd_searches[body["id"]] = body
            return httpx.Response(200, json={"id": body["id"], "isComplete": False})
        if path.startswith("/searches/"):
            parts = path.split("/")
            if request.method == "DELETE":
                return httpx.Response(204)
            if len(parts) == 4 and parts[3] == "responses":
                if self.slskd_responses_by_text is not None:
                    text = self.slskd_searches[parts[2]]["searchText"]
                    return httpx.Response(200, json=self.slskd_responses_by_text.get(text, []))
                return httpx.Response(200, json=self.slskd_responses)
            return httpx.Response(200, json={"id": parts[2], "isComplete": True, "state": "Completed, TimedOut"})
        if path.startswith("/transfers/downloads/"):
            parts = path.split("/")
            username = httpx.URL("http://x/" + parts[3]).path[1:]
            if request.method == "POST":
                if username in self.slskd_reject_users:
                    return httpx.Response(500, text="peer offline")
                for f in json.loads(request.content):
                    self.slskd_transfers.setdefault(username, {})[f["filename"]] = {
                        "id": f"t-{len(self.requests)}",
                        "filename": f["filename"],
                        "state": "Queued, Remotely",
                        "percentComplete": 0,
                    }
                return httpx.Response(201)
            if request.method == "DELETE":
                return httpx.Response(204)
            files = self.slskd_transfers.get(username)
            if files is None:
                return httpx.Response(404)
            return httpx.Response(
                200, json={"username": username, "directories": [{"directory": "x", "files": list(files.values())}]}
            )
        return httpx.Response(404)

    def set_transfer(self, username: str, filename: str, state: str, percent: float = 0) -> None:
        self.slskd_transfers[username][filename].update(state=state, percentComplete=percent)

    # endregion

    # region Lidarr

    def _lidarr(self, request: httpx.Request) -> httpx.Response:
        if request.headers.get("X-Api-Key") != "lidarr-key":
            return httpx.Response(401)
        path = request.url.path.removeprefix("/api/v1")
        if path == "/artist/lookup":
            term = request.url.params["term"]
            return httpx.Response(200, json=[{"artistName": term.title(), "foreignArtistId": f"mb-{term.lower()}"}])
        if path == "/artist" and request.method == "GET":
            return httpx.Response(200, json=self.lidarr_artists)
        if path == "/artist" and request.method == "POST":
            body = json.loads(request.content)
            artist = {
                **body,
                "id": len(self.lidarr_artists) + 1,
                "statistics": {"trackFileCount": 0, "totalTrackCount": 40},
            }
            self.lidarr_artists.append(artist)
            return httpx.Response(201, json=artist)
        if path.startswith("/artist/") and request.method == "GET":
            artist_id = int(path.rsplit("/", 1)[-1])
            artist = next((a for a in self.lidarr_artists if a["id"] == artist_id), None)
            return httpx.Response(200, json=artist) if artist else httpx.Response(404, json={"message": "NotFound"})
        if path.startswith("/artist/") and request.method == "PUT":
            self.lidarr_updates.append(json.loads(request.content))
            return httpx.Response(202, json=json.loads(request.content))
        if path == "/rootfolder":
            return httpx.Response(200, json=[{"id": 1, "path": "/music"}])
        if path in ("/qualityprofile", "/metadataprofile"):
            return httpx.Response(200, json=[{"id": 7}])
        if path == "/command":
            self.lidarr_commands.append(json.loads(request.content))
            return httpx.Response(201, json={"id": 1})
        if path == "/queue/details":
            return httpx.Response(200, json=[{"id": i} for i in range(self.lidarr_queue)])
        if path.startswith("/queue/") and request.method == "DELETE":
            self.lidarr_removed_from_queue.append(int(path.rsplit("/", 1)[-1]))
            return httpx.Response(200)
        return httpx.Response(404)

    # endregion

    def _yandex(self, request: httpx.Request) -> httpx.Response:
        if self.yandex_fails:
            return httpx.Response(451, text="unavailable")
        path = request.url.path
        if path == "/search":
            return httpx.Response(200, json={"result": self.yandex_search or {}})
        if path.endswith("/tracks"):
            return httpx.Response(200, json={"result": {"tracks": self.yandex_artist_tracks}})
        if path.endswith("/brief-info"):
            return httpx.Response(200, json={"result": {"popularTracks": self.yandex_artist_tracks}})
        return httpx.Response(404)

    def _deezer(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/search/track":
            return httpx.Response(200, json={"data": self.deezer_tracks})
        if path == "/search/artist":
            return httpx.Response(200, json={"data": self.deezer_artists})
        if path.endswith("/top"):
            return httpx.Response(200, json={"data": self.deezer_tracks})
        return httpx.Response(404)


class FakeClock:
    def __init__(self, start: float = 1_000_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds
