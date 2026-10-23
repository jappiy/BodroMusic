"""Привязка «пользователь приложения (Navidrome) → аккаунт Яндекса».

Архив рекомендаций хранится под логином Яндекса (имя файла токена в `/secrets/yandex_accounts/` или ключ
`YANDEX_ACCOUNTS`), а движок кандидатов, пул и загрузки работают от имени пользователя Navidrome. Сопоставление:
- `YANDEX_USERS=nikolai:jappi,olga:kalainidi` (пользователь Navidrome : логин Яндекса);
- то же из приложения (экран «Рекомендации» → «Архив Яндекса», хранится в БД bodrod и важнее переменной);
- без привязки, если файл токена назван именем пользователя Navidrome (как было до bodrod 0.15.0).

Если пользователь не привязан, источник «Архив Яндекса» пишет «Не привязан аккаунт Яндекса», а не «архив пуст».
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Iterable

from ..db import Store
from ..sources.yandex_tokens import request_owner

log = logging.getLogger(__name__)

LINKS_KEY = "yandex.links"
USERS_CACHE_S = 600
NOT_LINKED = "Не привязан аккаунт Яндекса (YANDEX_USERS или «Рекомендации» → «Архив Яндекса»)"


class YandexLinks:
    def __init__(
        self,
        store: Store | None,
        env_links: dict[str, str],
        accounts: Iterable[str],
        navidrome_users: Callable[[], Iterable[str]] | None = None,
    ) -> None:
        self._store = store
        self._env = {u: login for u, login in env_links.items() if u and login}
        self._accounts = set(accounts)
        self._navidrome_users = navidrome_users
        self._users_cache: tuple[float, set[str]] | None = None

    @property
    def accounts(self) -> list[str]:
        """Логины Яндекса, для которых есть токен."""
        return sorted(self._accounts)

    def _stored(self) -> dict[str, str | None]:
        return dict(self._store.get_value(LINKS_KEY) or {}) if self._store is not None else {}

    def all(self) -> dict[str, str]:
        """Пользователь Navidrome → логин Яндекса (из приложения поверх `YANDEX_USERS`; пустое значение — отвязан)."""
        links = dict(self._env)
        for user, login in self._stored().items():
            if login:
                links[user] = login
            else:
                links.pop(user, None)
        return links

    def _is_navidrome_user(self, name: str) -> bool:
        if self._navidrome_users is None:
            return True
        now = time.monotonic()
        if self._users_cache is None or now - self._users_cache[0] > USERS_CACHE_S:
            try:
                users = set(self._navidrome_users())
            except Exception as e:  # noqa: BLE001 — БД Navidrome недоступна: считаем имя пользователем, как раньше
                log.warning("Привязка Яндекса: не удалось прочитать пользователей Navidrome: %s", e)
                return True
            self._users_cache = (now, users)
        return name in self._users_cache[1]

    def yandex_login(self, user: str | None) -> str | None:
        """Логин Яндекса пользователя приложения (`pool:<user>` — владелец пула); `None` — не привязан."""
        owner = request_owner(user)
        if not owner:
            return None
        links = self.all()
        if owner in links:
            return links[owner]
        if owner in self._accounts and owner not in links.values():
            return owner  # токен в файле с именем пользователя Navidrome
        return None

    def navidrome_user(self, login: str) -> str | None:
        """Пользователь приложения, к которому привязан аккаунт Яндекса; `None` — ни к кому."""
        for user, linked in sorted(self.all().items()):
            if linked == login:
                return user
        if login in self._accounts and self._is_navidrome_user(login):
            return login
        return None

    def set(self, user: str, login: str | None) -> None:
        """Привязать (или отвязать — `login=None`) пользователя приложения из приложения."""
        if self._store is None:
            raise RuntimeError("Нет хранилища для привязок")
        stored = self._stored()
        stored[user] = (login or "").strip() or None
        self._store.set_value(LINKS_KEY, stored)
        log.info("Привязка Яндекса: %s → %s", user, stored[user] or "не привязан")
