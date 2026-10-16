"""HTTP API сервиса. Авторизация — те же учётные данные Subsonic, что и у Navidrome."""

import asyncio
import contextlib
import hashlib
import logging
import os
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response

from . import __version__
from .clients.lidarr import LidarrClient
from .clients.navidrome import Credentials, NavidromeClient
from .clients.slskd import SlskdClient
from .config import Settings
from .db import Store
from .metadata.discover import DiscoverService
from .metadata.providers import DeezerProvider, MetadataProvider, YandexMusicProvider
from .models import (
    CreateRequest,
    DiscoverResponse,
    DownloadRequestOut,
    RequestKind,
    ServiceInfo,
    TrackCandidate,
)
from .worker import Worker

log = logging.getLogger("bodrod")

AUTH_CACHE_TTL_S = 600


@dataclass
class Services:
    settings: Settings
    store: Store
    navidrome: NavidromeClient
    discover: DiscoverService
    worker: Worker
    auth_cache: dict[str, tuple[str, float]]


def build_services(settings: Settings, http: httpx.AsyncClient) -> Services:
    os.makedirs(settings.data_dir, exist_ok=True)
    store = Store(settings.db_path)
    navidrome = NavidromeClient(
        http, settings.navidrome_url, settings.navidrome_admin_user, settings.navidrome_admin_password
    )
    slskd = SlskdClient(http, settings.slskd_url, settings.slskd_api_key) if settings.slskd_enabled else None
    lidarr = (
        LidarrClient(
            http,
            settings.lidarr_url,
            settings.lidarr_api_key,
            settings.lidarr_root_folder,
            settings.lidarr_quality_profile_id,
            settings.lidarr_metadata_profile_id,
        )
        if settings.lidarr_enabled
        else None
    )
    providers: list[MetadataProvider] = [YandexMusicProvider(http, settings.yandex_token)]
    if settings.deezer_enabled:
        providers.append(DeezerProvider(http))
    return Services(
        settings=settings,
        store=store,
        navidrome=navidrome,
        discover=DiscoverService(providers, navidrome),
        worker=Worker(store, settings, navidrome, slskd, lidarr),
        auth_cache={},
    )


def create_app(services: Services | None = None, run_worker: bool = True) -> FastAPI:
    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        http: httpx.AsyncClient | None = None
        if services is None:
            http = httpx.AsyncClient(follow_redirects=True, headers={"User-Agent": f"bodrod/{__version__}"})
            app.state.services = build_services(Settings.from_env(), http)
        else:
            app.state.services = services
        task = asyncio.create_task(app.state.services.worker.run_forever()) if run_worker else None
        try:
            yield
        finally:
            if task is not None:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
            if http is not None:
                await http.aclose()
                app.state.services.store.close()

    app = FastAPI(title="bodrod", version=__version__, lifespan=lifespan)

    def get_services(request: Request) -> Services:
        return request.app.state.services

    async def current_user(
        request: Request,
        x_subsonic_user: Annotated[str | None, Header()] = None,
        x_subsonic_token: Annotated[str | None, Header()] = None,
        x_subsonic_salt: Annotated[str | None, Header()] = None,
    ) -> Credentials:
        if not (x_subsonic_user and x_subsonic_token and x_subsonic_salt):
            raise HTTPException(401, "Нужны заголовки X-Subsonic-User / X-Subsonic-Token / X-Subsonic-Salt")
        svc = get_services(request)
        creds = Credentials(x_subsonic_user, x_subsonic_token, x_subsonic_salt)
        key = hashlib.sha256(f"{creds.username}\0{creds.token}\0{creds.salt}".encode()).hexdigest()
        cached = svc.auth_cache.get(key)
        now = time.time()
        if cached and cached[1] > now:
            return creds
        try:
            ok = await svc.navidrome.check_user(creds)
        except Exception as e:
            log.warning("Navidrome недоступен для проверки учётки: %s", e)
            raise HTTPException(503, "Navidrome недоступен") from e
        if not ok:
            raise HTTPException(401, "Navidrome не принял учётные данные")
        if len(svc.auth_cache) > 10_000:
            svc.auth_cache.clear()
        svc.auth_cache[key] = (creds.username, now + AUTH_CACHE_TTL_S)
        return creds

    User = Annotated[Credentials, Depends(current_user)]
    Svc = Annotated[Services, Depends(get_services)]

    @app.get("/api/health")
    async def health() -> dict[str, str]:
        return {"status": "ok", "version": __version__}

    @app.get("/api/v1/info", response_model=ServiceInfo, response_model_by_alias=True)
    async def info(user: User, svc: Svc) -> ServiceInfo:
        return ServiceInfo(
            version=__version__,
            track_downloads=svc.settings.slskd_enabled,
            artist_downloads=svc.settings.lidarr_enabled,
            metadata_sources=svc.discover.source_names,
        )

    @app.get("/api/v1/discover", response_model=DiscoverResponse, response_model_by_alias=True)
    async def discover(
        user: User, svc: Svc, q: Annotated[str, Query(min_length=2, max_length=200)]
    ) -> DiscoverResponse:
        return await svc.discover.discover(q, user)

    @app.get("/api/v1/artists/top", response_model=list[TrackCandidate], response_model_by_alias=True)
    async def artist_top(
        user: User,
        svc: Svc,
        source: Annotated[str, Query()],
        id: Annotated[str, Query(alias="id")],
    ) -> list[TrackCandidate]:
        return await svc.discover.top_tracks(source, id, user)

    @app.get("/api/v1/requests", response_model=list[DownloadRequestOut], response_model_by_alias=True)
    async def list_requests(
        user: User, svc: Svc, limit: Annotated[int, Query(ge=1, le=500)] = 100
    ) -> list[DownloadRequestOut]:
        return [r.to_api() for r in svc.store.list_for_user(user.username, limit)]

    @app.post("/api/v1/requests", response_model=DownloadRequestOut, response_model_by_alias=True)
    async def create_request(user: User, svc: Svc, body: CreateRequest) -> JSONResponse:
        if body.kind == RequestKind.TRACK and not (body.title and body.title.strip()):
            raise HTTPException(422, "Для трека нужно название")
        if body.kind == RequestKind.TRACK and not svc.settings.slskd_enabled:
            raise HTTPException(409, "Скачивание треков не настроено (slskd)")
        if body.kind == RequestKind.ARTIST and not svc.settings.lidarr_enabled:
            raise HTTPException(409, "Скачивание исполнителей не настроено (Lidarr)")
        request, created = svc.store.create(
            username=user.username,
            kind=body.kind,
            artist=body.artist,
            title=body.title if body.kind == RequestKind.TRACK else None,
            album=body.album,
            duration_ms=body.duration_ms,
            cover_url=body.cover_url,
        )
        if created:
            log.info("%s: новая заявка %s %s — %s", user.username, body.kind, body.artist, body.title or "")
            svc.worker.wake()
        return JSONResponse(
            request.to_api().model_dump(by_alias=True, mode="json"), status_code=201 if created else 200
        )

    @app.get("/api/v1/requests/{request_id}", response_model=DownloadRequestOut, response_model_by_alias=True)
    async def get_request(user: User, svc: Svc, request_id: str) -> DownloadRequestOut:
        request = svc.store.get(request_id)
        if request is None or request.username != user.username:
            raise HTTPException(404, "Заявка не найдена")
        return request.to_api()

    @app.delete("/api/v1/requests/{request_id}", status_code=204, response_class=Response)
    async def delete_request(user: User, svc: Svc, request_id: str) -> Response:
        """Активную заявку отменяет, завершённую — удаляет из списка."""
        request = svc.store.get(request_id)
        if request is None or request.username != user.username:
            raise HTTPException(404, "Заявка не найдена")
        if request.status.is_active:
            await svc.worker.cancel(request)
        else:
            svc.store.delete(request_id)
        return Response(status_code=204)

    return app


def main() -> None:
    import uvicorn

    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    uvicorn.run(create_app(), host="0.0.0.0", port=int(os.environ.get("PORT", "8790")), log_level="info")  # noqa: S104


if __name__ == "__main__":
    main()
