"""Цикл воркера: взять задания у bodrod, посчитать признаки, отдать результат. Один файл за раз.

Переменные окружения:
- `BODROD_URL` — адрес bodrod (`http://bodrod:8790`);
- `ANALYZER_TOKEN` (или `ANALYZER_TOKEN_FILE`) — тот же токен, что у bodrod;
- `MUSIC_DIR` — папка музыки внутри контейнера (пути заданий — относительно неё), по умолчанию `/music`;
- `MODELS_DIR` — модели Essentia (в образе — `/models`);
- `BATCH` — сколько заданий брать за раз (10), `IDLE_S` — пауза, когда заданий нет (300).
"""

from __future__ import annotations

import logging
import os
import time
from collections.abc import Callable
from typing import Any, Protocol

import httpx

log = logging.getLogger("bodro_analyzer")


class Extractor(Protocol):
    def analyze(self, path: str) -> Any: ...


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name, "").strip()
    path = os.environ.get(f"{name}_FILE", "").strip()
    if not value and path:
        with open(path, encoding="utf-8") as f:
            value = f.read().strip()
    return value or default


class Worker:
    def __init__(
        self,
        http: httpx.Client,
        base_url: str,
        token: str,
        music_dir: str,
        extractor: Extractor,
        batch: int = 10,
        idle_s: float = 300,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._http = http
        self._base = base_url.rstrip("/")
        self._headers = {"X-Analyzer-Token": token}
        self._music_dir = music_dir
        self._extractor = extractor
        self._batch = batch
        self._idle_s = idle_s
        self._sleep = sleep

    def jobs(self) -> list[str]:
        response = self._http.get(
            f"{self._base}/api/v1/analysis/next", params={"limit": self._batch}, headers=self._headers, timeout=60
        )
        response.raise_for_status()
        return response.json().get("jobs") or []

    def process(self, relative: str) -> bool:
        """Посчитать и отправить один файл. `True` — признаки посчитаны."""
        local = os.path.join(self._music_dir, *relative.split("/"))
        body: dict[str, Any] = {"path": relative}
        ok = False
        if not os.path.isfile(local):
            body["error"] = f"Нет файла: {local}"
        else:
            started = time.monotonic()
            try:
                result = self._extractor.analyze(local)
                body.update({"features": result.features, "embedding": result.embedding})
                ok = True
                log.info("%s — %.1f с", relative, time.monotonic() - started)
            except Exception as e:  # noqa: BLE001 — битый файл не останавливает воркер
                body["error"] = f"{type(e).__name__}: {e}"[:500]
                log.warning("%s — ошибка: %s", relative, e)
        response = self._http.post(f"{self._base}/api/v1/analysis/result", json=body, headers=self._headers, timeout=60)
        response.raise_for_status()
        return ok

    def run_once(self) -> int:
        jobs = self.jobs()
        for relative in jobs:
            self.process(relative)
        return len(jobs)

    def run_forever(self) -> None:
        while True:
            try:
                if self.run_once() == 0:
                    self._sleep(self._idle_s)
            except httpx.HTTPError as e:
                log.warning("bodrod недоступен: %s — повтор через минуту", e)
                self._sleep(60)


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    token = _env("ANALYZER_TOKEN")
    base = _env("BODROD_URL")
    if not token or not base:
        raise SystemExit("Нужно задать BODROD_URL и ANALYZER_TOKEN")
    from .extract import EssentiaExtractor

    extractor = EssentiaExtractor(_env("MODELS_DIR", "/models") or "/models")
    worker = Worker(
        httpx.Client(),
        base,
        token,
        _env("MUSIC_DIR", "/music") or "/music",
        extractor,
        batch=int(_env("BATCH", "10") or 10),
        idle_s=float(_env("IDLE_S", "300") or 300),
    )
    log.info("Анализатор запущен: %s", base)
    worker.run_forever()
