"""Токены Яндекс Музыки: чей токен использовать и в каком он состоянии.

Токены аккаунтов — `/secrets/yandex_accounts/<логин Яндекса>` (или `YANDEX_ACCOUNTS`), общий — `YANDEX_MUSIC_TOKEN`.
Какой аккаунт у пользователя Navidrome — `reco/yandex_links.py` (`YANDEX_USERS`; без привязки — файл с именем
пользователя). Правило одно на весь bodrod (`YandexTokens.resolve`):
- загрузки — токен пользователя, который поставил заявку (для заявок пула `pool:<user>` — владелец пула),
  иначе общий; фоновые задачи (чарты, «метки» Lidarr, выгрузка) — токен владельца задачи, иначе общий;
- сбор данных аккаунта (лайки, история — `reco/harvest.py`) — только токен самого пользователя (`fallback=False`).

Каждый токен проверяется запросом к API (статус аккаунта и Плюс) при старте и раз в `YANDEX_CHECK_INTERVAL_S`;
результат кэшируется. «Не задан» и «недействителен» — разные состояния.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Literal

if TYPE_CHECKING:
    from .yandex import YandexApi

log = logging.getLogger(__name__)

POOL_USER_PREFIX = "pool:"
RETRY_ON_ERROR_S = 10 * 60
ORIGIN_TITLES = {"user": "токен пользователя", "global": "общий токен"}

Origin = Literal["user", "global"]
CheckState = Literal["ok", "no_plus", "invalid", "error"]


def request_owner(username: str | None) -> str | None:
    """Пользователь, от имени которого идёт заявка: `pool:nikolai` → `nikolai`."""
    if not username:
        return None
    return username.removeprefix(POOL_USER_PREFIX)


@dataclass(frozen=True)
class TokenRef:
    token: str
    origin: Origin
    """Чей токен: пользователь Navidrome для `user`, `None` для общего."""
    user: str | None = None

    @property
    def title(self) -> str:
        return ORIGIN_TITLES[self.origin]


@dataclass
class TokenCheck:
    state: CheckState
    checked_at: float
    error: str | None = None


def is_auth_error(error: BaseException) -> bool:
    """Токен не принят: 401/403 от API или `UnauthorizedError` библиотеки yandex-music."""
    name = type(error).__name__.lower()
    text = str(error).lower()
    return (
        "unauthorized" in name
        or "unauthorized" in text
        or "401" in text
        or "invalid token" in text
        or "token is invalid" in text
        or "oauth" in text
        and ("invalid" in text or "expired" in text)
    )


class YandexTokens:
    def __init__(
        self,
        user_tokens: dict[str, str],
        global_token: str | None,
        api_factory: Callable[[str | None], YandexApi],
        check_interval_s: int = 24 * 3600,
        clock: Callable[[], float] = time.time,
        link: Callable[[str], str | None] = lambda user: user,
    ) -> None:
        """`user_tokens` — аккаунт Яндекса → токен; `link` — пользователь Navidrome → аккаунт (`None` — не привязан)."""
        self._users = {u: t for u, t in user_tokens.items() if t}
        self.link = link
        self._global = global_token or None
        self._factory = api_factory
        self._check_interval_s = check_interval_s
        self._clock = clock
        self._apis: dict[str | None, YandexApi] = {}
        self._checks: dict[str, TokenCheck] = {}
        self._next_check: dict[str, float] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    @classmethod
    def from_api(cls, api: YandexApi, check_interval_s: int = 24 * 3600, clock: Callable[[], float] = time.time):
        """Один готовый API как общий токен (тесты и простая установка)."""
        token = "global" if api.has_token else None
        tokens = cls({}, token, lambda _t: api, check_interval_s, clock)
        tokens._apis = {token: api}
        return tokens

    @property
    def users(self) -> list[str]:
        """Аккаунты Яндекса с токеном."""
        return sorted(self._users)

    def account_token(self, account: str) -> str | None:
        return self._users.get(account)

    @property
    def has_global(self) -> bool:
        return self._global is not None

    @property
    def any_token(self) -> bool:
        return bool(self._users) or self._global is not None

    def resolve(self, user: str | None, fallback: bool = True) -> TokenRef | None:
        """Токен пользователя, затем (если `fallback`) общий. `None` — токена нет."""
        owner = request_owner(user)
        account = self.link(owner) if owner else None
        if owner and account in self._users:
            return TokenRef(self._users[account], "user", owner)
        if fallback and self._global:
            return TokenRef(self._global, "global")
        return None

    def api(self, ref: TokenRef | None) -> YandexApi:
        """API для токена (кэшируется по токену); `None` — без токена: только поиск и метаданные."""
        key = ref.token if ref else None
        api = self._apis.get(key)
        if api is None:
            api = self._factory(key)
            self._apis[key] = api
        return api

    def api_for(self, user: str | None, fallback: bool = True) -> tuple[YandexApi, TokenRef | None]:
        ref = self.resolve(user, fallback)
        return self.api(ref), ref

    def last_check(self, ref: TokenRef) -> TokenCheck | None:
        return self._checks.get(ref.token)

    async def check(self, ref: TokenRef, force: bool = False) -> TokenCheck | None:
        """Проверка токена запросом к API: при первом обращении, раз в `check_interval_s` и по кнопке."""
        lock = self._locks.setdefault(ref.token, asyncio.Lock())
        async with lock:
            now = self._clock()
            if not force and now < self._next_check.get(ref.token, 0.0):
                return self._checks.get(ref.token)
            who = f"пользователь {ref.user}" if ref.user else "общий токен"
            try:
                plus = await self.api(ref).has_plus()
                result = TokenCheck("ok" if plus else "no_plus", now)
                self._next_check[ref.token] = now + self._check_interval_s
                log.info("Яндекс Музыка (%s): токен действителен, Плюс %s", who, "есть" if plus else "НЕТ")
            except Exception as e:  # noqa: BLE001 — сеть или токен: состояние видно в приложении
                text = str(e) or type(e).__name__
                if is_auth_error(e):
                    result = TokenCheck("invalid", now, text)
                    self._next_check[ref.token] = now + self._check_interval_s
                    log.warning("Яндекс Музыка (%s): токен недействителен или истёк: %s", who, text)
                else:
                    result = TokenCheck("error", now, text)
                    self._next_check[ref.token] = now + RETRY_ON_ERROR_S
                    log.warning("Яндекс Музыка (%s): не удалось проверить токен: %s", who, text)
            self._checks[ref.token] = result
            return result

    async def check_all(self, force: bool = False) -> None:
        """Все известные токены (фоновая проверка раз в час; сама проверка — не чаще срока)."""
        refs = [TokenRef(t, "user", u) for u, t in self._users.items()]
        if self._global:
            refs.append(TokenRef(self._global, "global"))
        seen: set[str] = set()
        for ref in refs:
            if ref.token not in seen:
                seen.add(ref.token)
                await self.check(ref, force)

    def log_startup(self) -> None:
        """Для каких пользователей найден токен (сами токены не выводятся)."""
        users = ", ".join(self.users) or "нет"
        log.info(
            "Яндекс Музыка: токены пользователей — %s; общий токен YANDEX_MUSIC_TOKEN — %s",
            users,
            "задан" if self._global else "не задан",
        )
