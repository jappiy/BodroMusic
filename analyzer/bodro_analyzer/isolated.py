"""Анализ в дочернем процессе: нативный краш Essentia/TensorFlow убивает только его, а не воркер.

Дочерний процесс (`spawn`) один раз загружает модели и анализирует файлы по одному, получая пути через `Pipe`.
Если он умер (сигнал, `abort`, OOM) или не ответил за `timeout_s`, родитель убивает его, поднимает `AnalysisCrashed`
(воркер отправит в bodrod `failed`) и при следующем треке запускает новый. После `max_tasks` треков процесс
перезапускается, чтобы не копить утечки памяти нативных библиотек.
"""

from __future__ import annotations

import logging
import multiprocessing
import os
import signal
from collections.abc import Callable
from multiprocessing.connection import Connection, wait
from typing import Any

log = logging.getLogger("bodro_analyzer")


class AnalysisCrashed(RuntimeError):
    """Дочерний процесс умер или завис на треке — трек `failed`, следующий получит новый процесс."""


class ExtractorUnavailable(RuntimeError):
    """Модели не загружаются — это не ошибка трека: воркер останавливается (контейнер перезапустит Docker)."""


def essentia_factory(models_dir: str) -> Any:
    """Фабрика по умолчанию (модульная функция — передаётся в дочерний процесс по имени)."""
    from .extract import EssentiaExtractor  # noqa: PLC0415 — тяжёлый импорт только в дочернем процессе

    return EssentiaExtractor(models_dir)


def _child(conn: Connection, factory: Callable[[str], Any], models_dir: str) -> None:
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    try:
        extractor = factory(models_dir)
    except Exception as e:  # noqa: BLE001
        conn.send(("init_error", f"{type(e).__name__}: {e}"))
        return
    conn.send(("ready", None))
    while True:
        try:
            path = conn.recv()
        except EOFError:
            return
        if path is None:
            return
        try:
            conn.send(("ok", extractor.analyze(path)))
        except Exception as e:  # noqa: BLE001 — ошибка Python-уровня: процесс жив, трек — failed
            conn.send(("error", f"{type(e).__name__}: {e}"))


def _describe(exitcode: int | None) -> str:
    if exitcode is None:
        return "процесс не завершился"
    if exitcode < 0:
        try:
            return f"сигнал {signal.Signals(-exitcode).name}"
        except ValueError:
            return f"сигнал {-exitcode}"
    return f"код выхода {exitcode}"


class IsolatedExtractor:
    def __init__(
        self,
        models_dir: str,
        factory: Callable[[str], Any] = essentia_factory,
        timeout_s: float = 600,
        init_timeout_s: float = 600,
        max_tasks: int = 200,
    ) -> None:
        self._models_dir = models_dir
        self._factory = factory
        self._timeout_s = timeout_s
        self._init_timeout_s = init_timeout_s
        self._max_tasks = max_tasks
        self._ctx = multiprocessing.get_context("spawn")
        self._process: Any = None
        self._conn: Connection | None = None
        self._tasks = 0
        self._exitcode: int | None = None

    def _start(self) -> None:
        parent, child = self._ctx.Pipe()
        process = self._ctx.Process(
            target=_child, args=(child, self._factory, self._models_dir), name="analyze", daemon=True
        )
        process.start()
        child.close()
        self._process, self._conn, self._tasks = process, parent, 0
        try:
            kind, value = self._receive(self._init_timeout_s, "загрузка моделей")
        except AnalysisCrashed as e:
            raise ExtractorUnavailable(str(e)) from e
        if kind == "init_error":
            self.close()
            raise ExtractorUnavailable(f"Не удалось загрузить модели: {value}")

    def _receive(self, timeout: float, what: str) -> tuple[str, Any]:
        conn = self._conn
        assert conn is not None
        ready = wait([conn, self._process.sentinel], timeout)
        if conn in ready:
            try:
                return conn.recv()
            except (EOFError, OSError):
                pass  # процесс умер, не дописав ответ
        hung = not ready
        self.close()
        if hung:
            raise AnalysisCrashed(f"Анализ завис ({what} дольше {timeout:.0f} с), процесс убит")
        raise AnalysisCrashed(f"Процесс анализа упал ({what}): {_describe(self._exitcode)}")

    def analyze(self, path: str) -> Any:
        if self._conn is None or self._tasks >= self._max_tasks:
            self.close()
            self._start()
        assert self._conn is not None
        self._tasks += 1
        try:
            self._conn.send(path)
        except OSError:
            pass  # умер между треками — _receive опишет причину
        kind, value = self._receive(self._timeout_s, "анализ")
        if kind == "error":
            raise RuntimeError(value)
        return value

    def close(self) -> None:
        process, conn = self._process, self._conn
        self._process, self._conn = None, None
        if conn is not None:
            try:
                conn.send(None)
            except OSError:
                pass
            conn.close()
        if process is not None:
            process.join(2)
            if process.is_alive():
                process.kill()
                process.join(5)
            self._exitcode = process.exitcode
