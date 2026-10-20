"""Фабрики извлекателей для дочернего процесса (модульные функции — передаются в `spawn` по имени)."""

import os
import time

from bodro_analyzer.extract import Analysis


class Fake:
    def analyze(self, path: str) -> Analysis:
        name = os.path.basename(path)
        if name == "crash.flac":
            os._exit(3)  # как нативный краш: процесс умирает без исключения
        if name == "hang.flac":
            time.sleep(60)
        if name == "bad.flac":
            raise ValueError("не аудио")
        return Analysis(features={"pid": os.getpid()}, embedding=[0.5])


def fake_factory(models_dir: str) -> Fake:
    return Fake()


def broken_factory(models_dir: str) -> Fake:
    raise OSError(f"нет моделей в {models_dir}")
