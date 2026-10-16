"""slskd (Soulseek) REST API v0: поиск файлов и загрузки."""

from __future__ import annotations

import contextlib
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

import httpx


@dataclass(frozen=True)
class SlskdFile:
    filename: str
    size: int
    extension: str | None
    bit_rate: int | None
    length_s: int | None


@dataclass(frozen=True)
class SlskdResponse:
    username: str
    has_free_slot: bool
    queue_length: int
    upload_speed: int
    files: list[SlskdFile]


@dataclass(frozen=True)
class SlskdTransfer:
    id: str
    filename: str
    state: str
    percent: float | None

    @property
    def succeeded(self) -> bool:
        return self.state.startswith("Completed") and "Succeeded" in self.state

    @property
    def failed(self) -> bool:
        return self.state.startswith("Completed") and "Succeeded" not in self.state

    @property
    def in_progress(self) -> bool:
        return "InProgress" in self.state or "Initializing" in self.state


class SlskdClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, api_key: str) -> None:
        self._http = http
        self._base = base_url.rstrip("/") + "/api/v0"
        self._headers = {"X-API-Key": api_key}

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        response = await self._http.request(method, self._base + path, headers=self._headers, timeout=30, **kwargs)
        response.raise_for_status()
        return response

    async def start_search(self, text: str, timeout_s: int) -> str:
        search_id = str(uuid.uuid4())
        await self._request(
            "POST",
            "/searches",
            json={
                "id": search_id,
                "searchText": text,
                "searchTimeout": timeout_s * 1000,
                "filterResponses": True,
                "minimumResponseFileCount": 1,
                "responseLimit": 200,
                "fileLimit": 10000,
            },
        )
        return search_id

    async def search_complete(self, search_id: str) -> bool:
        data = (await self._request("GET", f"/searches/{search_id}")).json()
        return bool(data.get("isComplete")) or str(data.get("state", "")).startswith("Completed")

    async def search_responses(self, search_id: str) -> list[SlskdResponse]:
        data = (await self._request("GET", f"/searches/{search_id}/responses")).json()
        return [_parse_response(r) for r in data or []]

    async def delete_search(self, search_id: str) -> None:
        with contextlib.suppress(httpx.HTTPError):
            await self._request("DELETE", f"/searches/{search_id}")

    async def enqueue(self, username: str, filename: str, size: int) -> None:
        await self._request(
            "POST", f"/transfers/downloads/{_quote(username)}", json=[{"filename": filename, "size": size}]
        )

    async def transfer(self, username: str, filename: str) -> SlskdTransfer | None:
        response = await self._http.get(
            f"{self._base}/transfers/downloads/{_quote(username)}", headers=self._headers, timeout=30
        )
        if response.status_code == 404:
            return None
        response.raise_for_status()
        data = response.json() or {}
        for directory in data.get("directories") or []:
            for f in directory.get("files") or []:
                if f.get("filename") == filename:
                    return SlskdTransfer(
                        id=str(f.get("id")),
                        filename=filename,
                        state=str(f.get("state", "")),
                        percent=f.get("percentComplete"),
                    )
        return None

    async def cancel(self, username: str, transfer_id: str) -> None:
        with contextlib.suppress(httpx.HTTPError):
            await self._request("DELETE", f"/transfers/downloads/{_quote(username)}/{transfer_id}")


def _quote(value: str) -> str:
    return quote(value, safe="")


def _parse_response(raw: dict[str, Any]) -> SlskdResponse:
    files = []
    for f in raw.get("files") or []:
        filename = f.get("filename") or ""
        extension = f.get("extension") or (filename.rsplit(".", 1)[-1] if "." in filename else "")
        files.append(
            SlskdFile(
                filename=filename,
                size=int(f.get("size") or 0),
                extension=extension.lower().lstrip(".") or None,
                bit_rate=f.get("bitRate"),
                length_s=f.get("length"),
            )
        )
    return SlskdResponse(
        username=raw.get("username") or "",
        has_free_slot=bool(raw.get("hasFreeUploadSlot")),
        queue_length=int(raw.get("queueLength") or 0),
        upload_speed=int(raw.get("uploadSpeed") or 0),
        files=files,
    )
