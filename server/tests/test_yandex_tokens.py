"""Токены Яндекса: пользователя → общий, статус по пользователю, «не задан» и «недействителен», загрузка токеном
того, кто поставил заявку."""

import asyncio
import logging

from bodrod.models import RequestKind, RequestStatus
from bodrod.sources.base import FetchJob
from bodrod.sources.yandex import YandexSource
from bodrod.sources.yandex_tokens import YandexTokens, is_auth_error, request_owner

from .test_sources import Env, FakeWorld, FakeYandexApi, ytrack


class UnauthorizedError(Exception):
    """Как в yandex-music: токен не принят."""


class BadTokenApi(FakeYandexApi):
    async def has_plus(self) -> bool:
        self.plus_checks += 1
        raise UnauthorizedError("401 Unauthorized")


def tokens_with(apis: dict[str | None, FakeYandexApi], users: dict[str, str], global_token: str | None, clock=None):
    return YandexTokens(users, global_token, lambda token: apis[token], clock=clock or (lambda: 1000.0))


def test_resolve_user_then_global_and_strict_mode():
    tokens = YandexTokens({"jappi": "t-jappi"}, "t-global", lambda t: FakeYandexApi([]))
    assert tokens.resolve("jappi").origin == "user" and tokens.resolve("jappi").token == "t-jappi"
    assert tokens.resolve("pool:jappi").user == "jappi"  # заявка пула — владелец пула
    assert tokens.resolve("olga").origin == "global"
    assert tokens.resolve(None).origin == "global"
    assert tokens.resolve("olga", fallback=False) is None  # сбор данных — только свой токен
    assert YandexTokens({}, None, lambda t: FakeYandexApi([])).resolve("jappi") is None
    assert request_owner("pool:charts") == "charts" and request_owner(None) is None


def test_status_for_user_token_without_global(tmp_path):
    user_api = FakeYandexApi([], plus=True)
    anon = FakeYandexApi([], token=False)
    source = YandexSource(tokens_with({"t-jappi": user_api, None: anon}, {"jappi": "t-jappi"}, None), True)
    status = asyncio.run(source.status("jappi"))
    assert status.available and status.token_source == "user"
    assert status.details.startswith("Проверен")
    other = asyncio.run(source.status("olga"))
    assert not other.available
    assert other.reason == "Не задан токен: ни у пользователя olga, ни общий YANDEX_MUSIC_TOKEN"


def test_global_token_status_and_invalid_token_differs_from_missing():
    bad = BadTokenApi([])
    source = YandexSource(tokens_with({"t-global": bad}, {}, "t-global"), True)
    status = asyncio.run(source.status("jappi"))
    assert not status.available and status.token_source == "global"
    assert status.reason == "Токен недействителен или истёк (общий токен)"
    assert status.details.startswith("Проверен")
    asyncio.run(source.status("jappi"))
    assert bad.plus_checks == 1  # результат проверки кэшируется
    assert is_auth_error(UnauthorizedError("x")) and not is_auth_error(TimeoutError("timeout"))


def test_check_interval_and_network_error():
    class Flaky(FakeYandexApi):
        fail = True

        async def has_plus(self) -> bool:
            self.plus_checks += 1
            if self.fail:
                raise TimeoutError("timeout")
            return True

    now = [1000.0]
    api = Flaky([])
    tokens = tokens_with({"t": api}, {}, "t", clock=lambda: now[0])
    source = YandexSource(tokens, True)
    assert asyncio.run(source.status()).reason == "Не удалось проверить токен (общий токен): timeout"
    api.fail = False
    now[0] += 11 * 60  # после ошибки — повтор через 10 минут
    assert asyncio.run(source.status()).available
    assert api.plus_checks == 2


def test_download_uses_token_of_request_owner(tmp_path):
    global_api = FakeYandexApi([ytrack("g1", "Город Сочи")], plus=True)
    user_api = FakeYandexApi([ytrack("u1", "Город Сочи")], plus=True)
    env = Env(tmp_path, global_api, world=FakeWorld())
    tokens = tokens_with({"t-g": global_api, "t-u": user_api}, {"jappi": "t-u"}, "t-g", clock=env.clock)
    env.yandex = YandexSource(tokens, True, clock=env.clock)
    env.registry._direct["yandex"] = env.yandex
    mine = env.add(username="jappi")
    env.run(mine.id, RequestStatus.IMPORTING)
    assert user_api.downloads == ["u1"] and global_api.downloads == []


def test_fetch_job_owner_none_uses_global(tmp_path):
    global_api = FakeYandexApi([ytrack("g1", "Город Сочи")], plus=True)
    user_api = FakeYandexApi([ytrack("u1", "Город Сочи")], plus=True)
    source = YandexSource(tokens_with({"t-g": global_api, "t-u": user_api}, {"jappi": "t-u"}, "t-g"), True)
    assert source.api_for("charts") is global_api and source.api_for("jappi") is user_api

    async def no(*_args) -> bool:
        return False

    async def place(file) -> object:  # noqa: ANN001
        return file.path

    job = FetchJob(RequestKind.TRACK, "Трофим", "Город Сочи", None, None, tmp_path / "w", no, place, owner="lidarr")
    asyncio.run(source.fetch(job))
    assert global_api.downloads == ["g1"]


def test_startup_log_lists_users_without_tokens(caplog):
    tokens = YandexTokens({"jappi": "secret-token-1", "olga": "secret-token-2"}, None, lambda t: FakeYandexApi([]))
    with caplog.at_level(logging.INFO):
        tokens.log_startup()
    assert "jappi, olga" in caplog.text and "не задан" in caplog.text
    assert "secret-token" not in caplog.text
