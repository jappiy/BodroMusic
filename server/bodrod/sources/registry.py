"""Реестр источников загрузки: порядок, ручные переключатели, статусы, «Яндекс: метаданные».

Порядок и переключатели хранятся в БД bodrod (меняются в приложении), по умолчанию — `SOURCES_ORDER` и
`SOURCES_DISABLED`. «Выключен» — решение администратора; «недоступен» — источник не может работать сам
(нет подписки, не настроен, не запускается). И тот, и другой цепочка пропускает с записью в лог и в попытки заявки.
"""

from __future__ import annotations

import logging

from ..config import Settings
from ..db import Store
from ..models import RequestKind, SourceOut, SourcesOut
from .base import SOURCE_KINDS, SOURCE_TITLES, DirectSource, SourceStatus

log = logging.getLogger(__name__)

ORDER_KEY = "sources.order"
ENABLED_KEY = "sources.enabled"
METADATA_KEY = "sources.yandex_metadata"


class SourceRegistry:
    def __init__(
        self,
        store: Store,
        settings: Settings,
        direct: dict[str, DirectSource],
        slskd_ready: bool,
        lidarr_ready: bool,
    ) -> None:
        self._store = store
        self._settings = settings
        self._direct = direct
        self._slskd_ready = slskd_ready
        self._lidarr_ready = lidarr_ready

    def direct(self, name: str) -> DirectSource | None:
        return self._direct.get(name)

    def order(self) -> list[str]:
        saved = self._store.get_value(ORDER_KEY) or list(self._settings.sources_order)
        names = [n for n in dict.fromkeys(saved) if n in SOURCE_TITLES]
        return names + [n for n in SOURCE_TITLES if n not in names]

    def is_enabled(self, name: str) -> bool:
        saved = self._store.get_value(ENABLED_KEY) or {}
        return bool(saved.get(name, name not in self._settings.sources_disabled))

    def yandex_metadata(self) -> bool:
        return bool(self._store.get_value(METADATA_KEY, self._settings.yandex_metadata))

    def chain_for(self, kind: RequestKind) -> list[str]:
        """Источники, которые умеют этот вид заявки, в текущем порядке (включённость проверяется на шаге)."""
        return [n for n in self.order() if kind in SOURCE_KINDS[n]]

    async def status(self, name: str) -> SourceStatus:
        if name == "slskd":
            return (
                SourceStatus(True) if self._slskd_ready else SourceStatus(False, "Не заданы SLSKD_URL и SLSKD_API_KEY")
            )
        if name == "lidarr":
            return (
                SourceStatus(True)
                if self._lidarr_ready
                else SourceStatus(False, "Не заданы LIDARR_URL и LIDARR_API_KEY")
            )
        source = self._direct.get(name)
        if source is None:
            return SourceStatus(False, "Не настроен на сервере")
        try:
            return await source.status()
        except Exception as e:  # noqa: BLE001 — статус не должен ронять цепочку
            log.warning("Источник %s: статус не получен: %s", name, e)
            return SourceStatus(False, f"Ошибка проверки: {e}")

    async def usable(self, kind: RequestKind) -> bool:
        """Есть ли хоть один включённый и доступный источник для такого вида заявок."""
        for name in self.chain_for(kind):
            if self.is_enabled(name) and (await self.status(name)).available:
                return True
        return False

    def update(self, order: list[str] | None, enabled: dict[str, bool] | None, yandex_metadata: bool | None) -> None:
        if order is not None:
            unknown = [n for n in order if n not in SOURCE_TITLES]
            if unknown:
                raise ValueError(f"Неизвестные источники: {', '.join(unknown)}")
            full = list(dict.fromkeys(order)) + [n for n in self.order() if n not in order]
            self._store.set_value(ORDER_KEY, full)
            log.info("Порядок источников: %s", ", ".join(full))
        if enabled is not None:
            unknown = [n for n in enabled if n not in SOURCE_TITLES]
            if unknown:
                raise ValueError(f"Неизвестные источники: {', '.join(unknown)}")
            saved = {n: self.is_enabled(n) for n in SOURCE_TITLES} | {n: bool(v) for n, v in enabled.items()}
            self._store.set_value(ENABLED_KEY, saved)
            log.info("Источники: %s", ", ".join(f"{n}={'вкл' if v else 'выкл'}" for n, v in saved.items()))
        if yandex_metadata is not None:
            self._store.set_value(METADATA_KEY, bool(yandex_metadata))
            log.info("Яндекс: метаданные %s", "включены" if yandex_metadata else "выключены")

    async def describe(self) -> SourcesOut:
        items = []
        for name in self.order():
            status = await self.status(name)
            enabled = self.is_enabled(name)
            state = "disabled" if not enabled else "ok" if status.available else "unavailable"
            items.append(
                SourceOut(
                    name=name,
                    title=SOURCE_TITLES[name],
                    enabled=enabled,
                    state=state,
                    reason=status.reason,
                    kinds=[k.value for k in RequestKind if k in SOURCE_KINDS[name]],
                    details=status.details,
                )
            )
        return SourcesOut(
            sources=items,
            yandex_metadata=self.yandex_metadata(),
            yandex_metadata_reason=None if self._settings.yandex_token else "Без токена — без текстов песен",
        )
