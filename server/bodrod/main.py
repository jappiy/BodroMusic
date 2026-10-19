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
from .cleanup import CleanupError, CleanupService, _remap
from .clients.lidarr import LidarrClient
from .clients.navidrome import Credentials, NavidromeClient
from .clients.slskd import SlskdClient
from .config import Settings
from .db import MaintenanceAction, MaintenanceRun, Store
from .maintenance import MaintenanceError, MaintenanceService
from .metadata.discover import DiscoverService
from .metadata.lastfm import LastFmClient, LastFmProvider
from .metadata.lookup import build_chain
from .metadata.providers import DeezerProvider, MetadataProvider, YandexMusicProvider
from .models import (
    ArtistPopular,
    BlockEntryOut,
    BlocklistPage,
    CleanupUser,
    CreateRequest,
    DeleteSongResult,
    DeleteSongsRequest,
    DiscoverResponse,
    DislikedSong,
    DownloadRequestOut,
    ExportArtistOut,
    ExportArtistsUpdate,
    ExportStart,
    ExportStatus,
    HarvestAccount,
    HarvestStatus,
    LibraryCheck,
    LibraryCheckRequest,
    MaintenanceActionOut,
    MaintenanceActionsPage,
    MaintenanceRunOut,
    MaintenanceStatus,
    RequestKind,
    ServiceInfo,
    SourcesOut,
    SourcesUpdate,
    TrackCandidate,
)
from .navidrome_db import NavidromeDb, NdSong
from .reco.harvest import YandexHarvester
from .reco.library import LibraryIndex
from .reco.store import RecoStore
from .reco.yandex_account import YandexAccountApi
from .sources.base import KIND_NAMES, DirectSource
from .sources.export import YandexExport
from .sources.placer import Placer
from .sources.registry import SourceRegistry
from .sources.wanted import LidarrWantedSync
from .sources.yandex import YandexMetadata, YandexMusicApi, YandexSource
from .sources.youtube import YouTubeSource, YtDlpProcess, YtMusicClient
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
    cleanup: CleanupService | None = None
    maintenance: MaintenanceService | None = None
    navidrome_db: NavidromeDb | None = None
    yandex: YandexSource | None = None
    youtube: YouTubeSource | None = None
    export: YandexExport | None = None
    wanted: LidarrWantedSync | None = None
    reco: RecoStore | None = None
    library_index: LibraryIndex | None = None
    harvester: YandexHarvester | None = None


COVER_MAX_BYTES = 8 * 1024 * 1024
COVER_TIMEOUT_S = 10


def make_cover_fetcher(http: httpx.AsyncClient):
    """Обложка из каталога: только картинка, не больше 8 МБ (Яндекс 1000×1000), таймаут 10 с. `None` — нет."""

    async def fetch(url: str) -> tuple[bytes, str] | None:
        async with http.stream("GET", url, timeout=COVER_TIMEOUT_S) as response:
            response.raise_for_status()
            mime = response.headers.get("content-type", "").split(";")[0].strip().lower()
            if not mime.startswith("image/"):
                return None
            data = bytearray()
            async for chunk in response.aiter_bytes():
                data.extend(chunk)
                if len(data) > COVER_MAX_BYTES:
                    log.warning("Обложка больше 8 МБ, пропускаем: %s", url)
                    return None
            return bytes(data), mime

    return fetch


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
    # Источники загрузки: реестр знает порядок и переключатели; прямые источники — Яндекс и YouTube.
    direct: dict[str, DirectSource] = {}
    registry = SourceRegistry(store, settings, direct, slskd is not None, lidarr is not None)
    yandex_api = YandexMusicApi(settings.yandex_token, (settings.yandex_delay_min_s, settings.yandex_delay_max_s))
    library_ready = bool(settings.library_dir)
    yandex = YandexSource(yandex_api, library_ready, settings.yandex_check_interval_s, settings.preview_ratio)
    youtube = YouTubeSource(
        YtMusicClient(settings.youtube_proxy),
        YtDlpProcess(settings.ytdlp_dir, settings.youtube_proxy),
        library_ready,
        settings.preview_ratio,
        settings.ytdlp_auto_update,
        settings.ytdlp_update_interval_s,
    )
    direct.update({"yandex": yandex, "youtube": youtube})
    yandex_metadata = YandexMetadata(yandex_api, registry.yandex_metadata)
    log.info("Цепочка источников: %s", ", ".join(registry.order()))
    providers: list[MetadataProvider] = [YandexMusicProvider(http, settings.yandex_token, registry.yandex_metadata)]
    if settings.deezer_enabled:
        providers.append(DeezerProvider(http))
    lastfm = LastFmClient(http, settings.lastfm_api_key) if settings.lastfm_api_key else None
    catalogs = {p.name: p for p in providers} | ({"lastfm": LastFmProvider(lastfm)} if lastfm else {})
    discover_providers = [catalogs[name] for name in settings.discover_providers if name in catalogs]
    log.info("Каталоги поиска: %s", ", ".join(p.name for p in discover_providers) or "нет")
    cover_fetcher = make_cover_fetcher(http)
    navidrome_db = NavidromeDb(settings.navidrome_db) if settings.navidrome_db else None
    files_lock = asyncio.Lock()
    cleanup = CleanupService(settings, store, navidrome, navidrome_db, lidarr, files_lock) if navidrome_db else None
    maintenance = None
    if navidrome_db and settings.maintenance_enabled:
        cover_chain = build_chain(settings.cover_providers, http, providers, settings.discogs_token, lastfm)
        tag_chain = build_chain(settings.tag_providers, http, providers, settings.discogs_token, lastfm)
        log.info(
            "Источники обложек: %s; тегов: %s",
            ", ".join(p.name for p in cover_chain) or "нет",
            ", ".join(p.name for p in tag_chain if p.tags) or "нет",
        )
        maintenance = MaintenanceService(
            settings, store, navidrome, navidrome_db, lidarr, cover_chain, tag_chain, cover_fetcher, files_lock
        )
    placer = Placer(settings, store, cover_fetcher, yandex_metadata)
    worker = Worker(
        store, settings, navidrome, slskd, lidarr, cover_fetcher=cover_fetcher, registry=registry, placer=placer
    )
    wanted = None
    if lidarr is not None and settings.lidarr_wanted_sync:
        wanted = LidarrWantedSync(store, settings, lidarr, worker)
    export = YandexExport(store, settings, yandex, placer, worker, navidrome) if library_ready else None
    reco = RecoStore(store)
    pool_prefix = _remap(settings.discovery_path, settings.music_dir, settings.navidrome_music_dir)
    library_index = LibraryIndex(navidrome_db, navidrome, pool_prefix)
    harvester = None
    if settings.harvest_enabled and settings.yandex_accounts:
        delay = (settings.harvest_delay_s, settings.harvest_delay_s * 1.2)
        accounts = {u: YandexAccountApi(u, t, delay) for u, t in settings.yandex_accounts.items()}
        harvester = YandexHarvester(store, reco, settings, accounts, library_index, placer if library_ready else None)
        log.info("Архив Яндекса: аккаунты %s", ", ".join(accounts))
    return Services(
        settings=settings,
        store=store,
        navidrome=navidrome,
        discover=DiscoverService(discover_providers, navidrome),
        worker=worker,
        auth_cache={},
        cleanup=cleanup,
        maintenance=maintenance,
        navidrome_db=navidrome_db,
        yandex=yandex,
        youtube=youtube,
        export=export,
        wanted=wanted,
        reco=reco,
        library_index=library_index,
        harvester=harvester,
    )


SOURCES_CHECK_S = 3600


async def sources_upkeep(svc: Services) -> None:
    """Подписка Плюс и обновление yt-dlp (у каждого свой срок, по умолчанию сутки): при старте и раз в час."""
    while True:
        try:
            if svc.yandex is not None:
                await svc.yandex.check_subscription()
            if svc.youtube is not None:
                await svc.youtube.auto_update()
        except Exception:  # noqa: BLE001 — проверим через час
            log.exception("Проверка источников загрузки")
        await asyncio.sleep(SOURCES_CHECK_S)


def create_app(services: Services | None = None, run_worker: bool = True) -> FastAPI:
    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        http: httpx.AsyncClient | None = None
        if services is None:
            http = httpx.AsyncClient(follow_redirects=True, headers={"User-Agent": f"bodrod/{__version__}"})
            app.state.services = build_services(Settings.from_env(), http)
        else:
            app.state.services = services
        tasks = []
        if run_worker:
            tasks.append(asyncio.create_task(app.state.services.worker.run_forever()))
            if app.state.services.cleanup is not None:
                tasks.append(asyncio.create_task(app.state.services.cleanup.run_forever()))
            if app.state.services.maintenance is not None:
                tasks.append(asyncio.create_task(app.state.services.maintenance.run_forever()))
            for extra in (app.state.services.wanted, app.state.services.export, app.state.services.harvester):
                if extra is not None:
                    tasks.append(asyncio.create_task(extra.run_forever()))
            tasks.append(asyncio.create_task(sources_upkeep(app.state.services)))
        try:
            yield
        finally:
            for task in tasks:
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
        registry = svc.worker.registry
        return ServiceInfo(
            version=__version__,
            track_downloads=await registry.usable(RequestKind.TRACK),
            artist_downloads=await registry.usable(RequestKind.ARTIST),
            album_downloads=await registry.usable(RequestKind.ALBUM),
            metadata_sources=svc.discover.source_names,
            cleanup=svc.cleanup is not None,
            admin=svc.cleanup is not None and await svc.cleanup.is_admin(user.username),
            maintenance=svc.maintenance is not None,
            sources=svc.cleanup is not None,
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

    @app.get("/api/v1/artists/popular", response_model=ArtistPopular, response_model_by_alias=True)
    async def artist_popular(
        user: User, svc: Svc, name: Annotated[str, Query(min_length=1, max_length=200)]
    ) -> ArtistPopular:
        return await svc.discover.artist_popular(name, user)

    @app.post("/api/v1/library/check", response_model=LibraryCheck, response_model_by_alias=True)
    async def library_check(user: User, svc: Svc, body: LibraryCheckRequest) -> LibraryCheck:
        """Какие треки (например, из «Любимого») удалены из библиотеки или заменены другой копией."""
        db = svc.navidrome_db
        if db is None:
            raise HTTPException(409, "Не настроено: задайте NAVIDROME_DB")
        ids = list(dict.fromkeys(body.ids))
        present = await asyncio.to_thread(db.present_ids, ids)
        gone = [i for i in ids if i not in present]
        kept_paths = svc.store.replacements(gone) if gone else {}
        by_path = await asyncio.to_thread(db.ids_by_path, list(set(kept_paths.values()))) if kept_paths else {}
        replaced = {old: by_path[path] for old, path in kept_paths.items() if path in by_path}
        return LibraryCheck(missing=[i for i in gone if i not in replaced], replaced=replaced)

    @app.get("/api/v1/requests", response_model=list[DownloadRequestOut], response_model_by_alias=True)
    async def list_requests(
        user: User, svc: Svc, limit: Annotated[int, Query(ge=1, le=500)] = 100
    ) -> list[DownloadRequestOut]:
        return [r.to_api() for r in svc.store.list_for_user(user.username, limit)]

    @app.post("/api/v1/requests", response_model=DownloadRequestOut, response_model_by_alias=True)
    async def create_request(user: User, svc: Svc, body: CreateRequest) -> JSONResponse:
        if body.kind == RequestKind.TRACK and not (body.title and body.title.strip()):
            raise HTTPException(422, "Для трека нужно название")
        if body.kind == RequestKind.ALBUM and not (body.album and body.album.strip()):
            raise HTTPException(422, "Для альбома нужно название альбома")
        if not await svc.worker.registry.usable(body.kind):
            raise HTTPException(409, f"Нет доступных источников, которые скачивают {KIND_NAMES[body.kind]}")
        if body.kind == RequestKind.TRACK and svc.store.is_blocked(body.artist, body.title):
            raise HTTPException(409, "Трек в чёрном списке: его удалили с сервера")
        request, created = svc.store.create(
            username=user.username,
            kind=body.kind,
            artist=body.artist,
            title=body.title if body.kind == RequestKind.TRACK else None,
            album=body.album,
            duration_ms=body.duration_ms,
            cover_url=body.cover_url,
        )
        if not created:
            svc.worker.wake()  # заявка могла ожить после ошибки
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

    # region «На удаление» и чёрный список (только администраторы Navidrome)

    async def admin_cleanup(user: User, svc: Svc) -> CleanupService:
        if svc.cleanup is None:
            raise HTTPException(409, "Раздел «На удаление» не настроен: задайте NAVIDROME_DB")
        if not await svc.cleanup.is_admin(user.username):
            raise HTTPException(403, "Доступно только администраторам")
        return svc.cleanup

    Cleanup = Annotated[CleanupService, Depends(admin_cleanup)]

    def song_out(song: NdSong, settings: Settings) -> DislikedSong:
        root = settings.navidrome_music_dir.rstrip("/") + "/"
        return DislikedSong(
            id=song.id,
            title=song.title,
            artist=song.artist,
            album=song.album,
            album_id=song.album_id,
            duration=int(song.duration_s) if song.duration_s is not None else None,
            size=song.size,
            suffix=song.suffix,
            bit_rate=song.bit_rate,
            path=song.path.removeprefix(root),
            disliked_by=song.disliked_by,
        )

    @app.get("/api/v1/cleanup/users", response_model=list[CleanupUser], response_model_by_alias=True)
    async def cleanup_users(cleanup: Cleanup) -> list[CleanupUser]:
        return [CleanupUser(username=u.username, is_admin=u.is_admin) for u in await cleanup.users()]

    @app.get("/api/v1/cleanup/disliked", response_model=list[DislikedSong], response_model_by_alias=True)
    async def cleanup_disliked(
        cleanup: Cleanup, svc: Svc, username: Annotated[str | None, Query(max_length=200)] = None
    ) -> list[DislikedSong]:
        """Без `username` — треки, которые не нравятся всем пользователям (кроме служебных)."""
        try:
            songs = await cleanup.disliked(username)
        except CleanupError as e:
            raise HTTPException(404, str(e)) from e
        return [song_out(s, svc.settings) for s in songs]

    @app.post("/api/v1/cleanup/delete", response_model=list[DeleteSongResult], response_model_by_alias=True)
    async def cleanup_delete(cleanup: Cleanup, user: User, body: DeleteSongsRequest) -> list[DeleteSongResult]:
        results = await cleanup.delete(list(dict.fromkeys(body.ids)), user.username)
        return [DeleteSongResult(id=r.id, ok=r.ok, message=r.message) for r in results]

    @app.get("/api/v1/blocklist", response_model=BlocklistPage, response_model_by_alias=True)
    async def blocklist(
        cleanup: Cleanup,
        svc: Svc,
        q: Annotated[str | None, Query(max_length=200)] = None,
        limit: Annotated[int, Query(ge=1, le=500)] = 100,
        offset: Annotated[int, Query(ge=0)] = 0,
    ) -> BlocklistPage:
        total, items = svc.store.list_blocked(q, limit, offset)
        return BlocklistPage(total=total, items=[BlockEntryOut(**e.__dict__) for e in items])

    @app.delete("/api/v1/blocklist/{entry_id}", status_code=204, response_class=Response)
    async def unblock(cleanup: Cleanup, user: User, svc: Svc, entry_id: int) -> Response:
        entry = svc.store.unblock(entry_id)
        if entry is None:
            raise HTTPException(404, "Запись не найдена")
        log.info("%s убрал из чёрного списка: %s — %s", user.username, entry.artist, entry.title)
        return Response(status_code=204)

    # endregion

    # region Обслуживание библиотеки (только администраторы Navidrome)

    async def admin_maintenance(user: User, svc: Svc) -> MaintenanceService:
        if svc.maintenance is None or svc.cleanup is None:
            raise HTTPException(409, "Обслуживание библиотеки не настроено: задайте NAVIDROME_DB")
        if not await svc.cleanup.is_admin(user.username):
            raise HTTPException(403, "Доступно только администраторам")
        return svc.maintenance

    Maintenance = Annotated[MaintenanceService, Depends(admin_maintenance)]

    def run_out(run: MaintenanceRun) -> MaintenanceRunOut:
        return MaintenanceRunOut(**run.__dict__)

    def action_out(action: MaintenanceAction) -> MaintenanceActionOut:
        return MaintenanceActionOut(**action.__dict__)

    @app.get("/api/v1/maintenance", response_model=MaintenanceStatus, response_model_by_alias=True)
    async def maintenance_status(maintenance: Maintenance, svc: Svc) -> MaintenanceStatus:
        runs = svc.store.list_runs(1)
        return MaintenanceStatus(
            enabled=True,
            running=maintenance.running,
            dry_run=svc.settings.maintenance_dry_run,
            schedule=svc.settings.maintenance_at,
            next_run_at=int(maintenance.next_run_at() * 1000),
            quarantine_days=svc.settings.quarantine_days,
            last_run=run_out(runs[0]) if runs else None,
        )

    @app.get("/api/v1/maintenance/runs", response_model=list[MaintenanceRunOut], response_model_by_alias=True)
    async def maintenance_runs(
        maintenance: Maintenance, svc: Svc, limit: Annotated[int, Query(ge=1, le=100)] = 20
    ) -> list[MaintenanceRunOut]:
        return [run_out(r) for r in svc.store.list_runs(limit)]

    @app.get("/api/v1/maintenance/actions", response_model=MaintenanceActionsPage, response_model_by_alias=True)
    async def maintenance_actions(
        maintenance: Maintenance,
        svc: Svc,
        run_id: Annotated[int | None, Query(alias="runId")] = None,
        kind: Annotated[str | None, Query(max_length=20)] = None,
        limit: Annotated[int, Query(ge=1, le=500)] = 100,
        offset: Annotated[int, Query(ge=0)] = 0,
    ) -> MaintenanceActionsPage:
        total, items = svc.store.list_actions(run_id, kind, limit, offset)
        return MaintenanceActionsPage(total=total, items=[action_out(a) for a in items])

    @app.post("/api/v1/maintenance/run", status_code=202, response_class=Response)
    async def maintenance_run(maintenance: Maintenance, user: User) -> Response:
        if not maintenance.start("manual"):
            raise HTTPException(409, "Обслуживание уже идёт")
        log.info("%s запустил обслуживание библиотеки", user.username)
        return Response(status_code=202)

    @app.post("/api/v1/maintenance/quarantine/{entry_id}/restore", status_code=204, response_class=Response)
    async def maintenance_restore(maintenance: Maintenance, user: User, entry_id: int) -> Response:
        try:
            await maintenance.restore(entry_id)
        except MaintenanceError as e:
            raise HTTPException(409, str(e)) from e
        log.info("%s вернул файл из карантина (%s)", user.username, entry_id)
        return Response(status_code=204)

    # endregion

    # region Источники загрузки и выгрузка из Яндекса (только администраторы Navidrome)

    async def admin_user(user: User, svc: Svc) -> Credentials:
        if svc.cleanup is None:
            raise HTTPException(409, "Не настроено: задайте NAVIDROME_DB (нужно для проверки администратора)")
        if not await svc.cleanup.is_admin(user.username):
            raise HTTPException(403, "Доступно только администраторам")
        return user

    Admin = Annotated[Credentials, Depends(admin_user)]

    @app.get("/api/v1/sources", response_model=SourcesOut, response_model_by_alias=True)
    async def sources(admin: Admin, svc: Svc) -> SourcesOut:
        return await svc.worker.registry.describe()

    @app.put("/api/v1/sources", response_model=SourcesOut, response_model_by_alias=True)
    async def update_sources(admin: Admin, svc: Svc, body: SourcesUpdate) -> SourcesOut:
        try:
            svc.worker.registry.update(body.order, body.enabled, body.yandex_metadata)
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        log.info("%s изменил источники загрузки", admin.username)
        svc.worker.wake()
        return await svc.worker.registry.describe()

    @app.post("/api/v1/sources/yandex/check", response_model=SourcesOut, response_model_by_alias=True)
    async def check_yandex(admin: Admin, svc: Svc) -> SourcesOut:
        if svc.yandex is not None:
            await svc.yandex.check_subscription(force=True)
        return await svc.worker.registry.describe()

    @app.post("/api/v1/sources/youtube/update", response_model=SourcesOut, response_model_by_alias=True)
    async def update_ytdlp(admin: Admin, svc: Svc) -> SourcesOut:
        if svc.youtube is None:
            raise HTTPException(409, "YouTube Music не настроен")
        try:
            await svc.youtube.update_ytdlp()
        except Exception as e:  # noqa: BLE001 — показать причину в приложении
            raise HTTPException(502, f"yt-dlp не обновлён: {e}") from e
        return await svc.worker.registry.describe()

    def require_export(svc: Services) -> YandexExport:
        if svc.export is None:
            raise HTTPException(409, "Выгрузка не настроена: задайте LIBRARY_DIR")
        return svc.export

    def artists_out(svc: Services) -> list[ExportArtistOut]:
        return [
            ExportArtistOut(id=a["artist_id"], name=a["name"], liked=a["liked"], selected=bool(a["selected"]))
            for a in svc.store.export_artists()
        ]

    @app.get("/api/v1/yandex/export", response_model=ExportStatus, response_model_by_alias=True)
    async def export_status(admin: Admin, svc: Svc) -> ExportStatus:
        return require_export(svc).status()

    @app.post("/api/v1/yandex/export/start", response_model=ExportStatus, response_model_by_alias=True)
    async def export_start(admin: Admin, svc: Svc, body: ExportStart) -> ExportStatus:
        export = require_export(svc)
        await export.start(body.likes, body.playlists, body.artists)
        log.info("%s запустил выгрузку из Яндекса", admin.username)
        return export.status()

    @app.post("/api/v1/yandex/export/pause", response_model=ExportStatus, response_model_by_alias=True)
    async def export_pause(admin: Admin, svc: Svc) -> ExportStatus:
        export = require_export(svc)
        export.pause()
        return export.status()

    @app.get("/api/v1/yandex/export/artists", response_model=list[ExportArtistOut], response_model_by_alias=True)
    async def export_artists(admin: Admin, svc: Svc) -> list[ExportArtistOut]:
        require_export(svc)
        return artists_out(svc)

    @app.post(
        "/api/v1/yandex/export/artists/refresh", response_model=list[ExportArtistOut], response_model_by_alias=True
    )
    async def export_artists_refresh(admin: Admin, svc: Svc) -> list[ExportArtistOut]:
        export = require_export(svc)
        try:
            await export.refresh_artists()
        except Exception as e:  # noqa: BLE001 — токен, сеть
            raise HTTPException(502, f"Яндекс не отдал «Мне нравится»: {e}") from e
        return artists_out(svc)

    @app.put("/api/v1/yandex/export/artists", response_model=list[ExportArtistOut], response_model_by_alias=True)
    async def export_select(admin: Admin, svc: Svc, body: ExportArtistsUpdate) -> list[ExportArtistOut]:
        require_export(svc).select_artists(body.selected)
        return artists_out(svc)

    # endregion

    # region Архив Яндекса для рекомендаций (только администраторы)

    @app.get("/api/v1/reco/harvest", response_model=HarvestStatus, response_model_by_alias=True)
    async def harvest_status(admin: Admin, svc: Svc) -> HarvestStatus:
        if svc.harvester is None:
            return HarvestStatus(enabled=False, accounts=[])
        return HarvestStatus(enabled=True, accounts=[HarvestAccount(**a) for a in svc.harvester.status()])

    @app.post("/api/v1/reco/harvest/run", response_model=HarvestStatus, response_model_by_alias=True)
    async def harvest_run(admin: Admin, svc: Svc) -> HarvestStatus:
        if svc.harvester is None:
            raise HTTPException(409, "Архив Яндекса не настроен: задайте YANDEX_ACCOUNTS")
        svc.harvester.wake()
        return HarvestStatus(enabled=True, accounts=[HarvestAccount(**a) for a in svc.harvester.status()])

    # endregion

    return app


def main() -> None:
    import sys

    import uvicorn

    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    log.info(
        "bodrod %s, кодировка файловой системы: %s, UTF-8 mode: %s",
        __version__,
        sys.getfilesystemencoding(),
        sys.flags.utf8_mode,
    )
    uvicorn.run(create_app(), host="0.0.0.0", port=int(os.environ.get("PORT", "8790")), log_level="info")  # noqa: S104


if __name__ == "__main__":
    main()
