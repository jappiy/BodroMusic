"""Настройки сервиса из переменных окружения."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field

DEFAULT_FORMATS = ("flac", "mp3", "m4a", "ogg", "opus", "aac", "wav", "alac")


"""Last.fm — в конце: дополнительный канал, срабатывает, когда остальные не нашли или не ответили."""
DEFAULT_COVER_PROVIDERS = ("yandex", "deezer", "itunes", "musicbrainz", "lastfm")
DEFAULT_TAG_PROVIDERS = ("musicbrainz", "yandex", "deezer", "lastfm")
DEFAULT_DISCOVER_PROVIDERS = ("yandex", "deezer", "lastfm")
"""Цепочка источников загрузки: Яндекс Музыка → Soulseek → YouTube Music → Lidarr (последний вариант)."""
DEFAULT_SOURCES_ORDER = ("yandex", "slskd", "youtube", "lidarr")
SECRETS_DIR = "/secrets"
_COMMENT = re.compile(r"\s+#.*$")


def _opt(name: str, default_file: str | None = None) -> str | None:
    """Значение переменной; `<NAME>_FILE` — путь к файлу с секретом (например, смонтированному в /secrets).

    `default_file` — файл, который читается, если не задано ни то, ни другое (пароль в `/secrets`).
    """
    value = os.environ.get(name, "").strip()
    path = os.environ.get(f"{name}_FILE", "").strip()
    if not value and not path and default_file and os.path.isfile(default_file):
        path = default_file
    if not value and path:
        with open(path, encoding="utf-8") as f:
            value = f.read().strip()
    return value or None


def _secret(name: str, default_file: str | None = None) -> str | None:
    """Ключ для HTTP-заголовка: без комментария `# …` в конце строки и только ASCII.

    Кириллица в заголовке роняет httpx ошибкой `'ascii' codec can't encode` на каждом запросе — лучше
    остановиться при старте с понятным сообщением.
    """
    value = _opt(name, default_file)
    if value is None:
        return None
    value = _COMMENT.sub("", value.splitlines()[0]).strip().strip("\"'")
    if not value.isascii():
        raise RuntimeError(f"{name}: в значении есть не-ASCII символы (комментарий или опечатка?) — проверьте секрет")
    return value or None


def _list(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    value = _opt(name)
    if value is None:
        return default
    return tuple(p.strip().lower() for p in value.split(",") if p.strip())


def _int(name: str, default: int) -> int:
    value = _opt(name)
    return int(value) if value else default


def _range(name: str, default: tuple[float, float]) -> tuple[float, float]:
    """`2-3` → (2.0, 3.0); `2.5` → (2.5, 2.5)."""
    value = _opt(name)
    if not value:
        return default
    low, _, high = value.partition("-")
    return float(low), float(high or low)


def _bool(name: str, default: bool) -> bool:
    value = _opt(name)
    return default if value is None else value.lower() not in ("false", "0", "no", "off")


@dataclass(frozen=True)
class Settings:
    navidrome_url: str
    """Учётка администратора Navidrome: проверка «есть ли трек в библиотеке» и запуск сканирования."""
    navidrome_admin_user: str
    navidrome_admin_password: str

    slskd_url: str | None = None
    slskd_api_key: str | None = None

    lidarr_url: str | None = None
    lidarr_api_key: str | None = None
    lidarr_root_folder: str | None = None
    lidarr_quality_profile_id: int | None = None
    lidarr_metadata_profile_id: int | None = None

    yandex_token: str | None = None
    deezer_enabled: bool = True
    """Каталоги глобального поиска и популярного исполнителя, по порядку: yandex, deezer, lastfm."""
    discover_providers: tuple[str, ...] = DEFAULT_DISCOVER_PROVIDERS
    """Источники обложек и тегов для обслуживания, по порядку: yandex, deezer, itunes, musicbrainz, discogs, lastfm."""
    cover_providers: tuple[str, ...] = DEFAULT_COVER_PROVIDERS
    tag_providers: tuple[str, ...] = DEFAULT_TAG_PROVIDERS
    """Токен Discogs (необязательно): без него Discogs в цепочке пропускается."""
    discogs_token: str | None = None
    """Ключ API Last.fm (необязательно): без него Last.fm везде пропускается."""
    lastfm_api_key: str | None = None

    data_dir: str = "/data"
    worker_interval_s: float = 3.0
    slskd_search_timeout_s: int = 25
    slskd_max_candidates: int = 5
    """Сколько поисков в Soulseek идёт одновременно и пауза между их запусками (секунды)."""
    slskd_parallel_searches: int = 2
    slskd_search_gap_s: int = 5
    """Не нашли ни по одному запросу — повторить через столько секунд, не больше `slskd_retry_rounds` раз."""
    slskd_retry_interval_s: int = 6 * 3600
    slskd_retry_rounds: int = 3
    """Сколько ждать, пока пир начнёт отдавать файл (очередь у него), прежде чем пробовать следующий источник."""
    slskd_stall_timeout_s: int = 20 * 60
    import_timeout_s: int = 15 * 60
    lidarr_check_interval_s: int = 60
    lidarr_give_up_s: int = 24 * 3600
    allowed_formats: tuple[str, ...] = field(default=DEFAULT_FORMATS)
    min_bitrate: int = 192

    """Папка загрузок slskd, как она смонтирована в контейнер bodrod."""
    slskd_downloads_dir: str | None = None
    """Куда bodrod кладёт скачанные треки (внутри библиотеки Navidrome)."""
    library_dir: str | None = None
    library_layout: str = "{artist}/{album}/{artist} - {title}"
    delete_source: bool = True
    place_find_timeout_s: int = 60

    """Файл БД Navidrome (`navidrome.db`): дизлайки всех пользователей для раздела «На удаление»."""
    navidrome_db: str | None = None
    """Служебные учётки Navidrome: их дизлайки не учитываются, в списках их нет. Админ bodrod — всегда служебный."""
    service_users: tuple[str, ...] = ()
    """Корень музыки так, как его видит Navidrome, и так, как он смонтирован в bodrod."""
    navidrome_music_dir: str = "/music"
    music_dir: str = "/music"
    """Корень музыки так, как его видит Lidarr (для удаления файла через Lidarr). Пусто — Lidarr не трогаем."""
    lidarr_music_dir: str | None = None
    """Как часто искать и удалять заново появившиеся треки из чёрного списка."""
    blocklist_sweep_s: int = 15 * 60
    """Очередь slsk-worker («Исполнитель - Трек» построчно): из неё убираются заблокированные треки."""
    slsk_worker_queue: str | None = None

    """Регламент «Обслуживание библиотеки» (теги, обложки, дубли, короткие файлы). Нужен NAVIDROME_DB."""
    maintenance_enabled: bool = True
    """Время ежедневного запуска (часы:минуты, часовой пояс контейнера — переменная TZ)."""
    maintenance_at: str = "04:00"
    """Пробный режим: только отчёт, файлы не меняются."""
    maintenance_dry_run: bool = False
    """Папка карантина (убранные дубли и короткие файлы). Пусто — `<MUSIC_DIR>/.bodrod-quarantine`."""
    quarantine_dir: str | None = None
    quarantine_days: int = 30
    """Сколько запросов к каталогу (поиск тегов и обложек) за один запуск."""
    maintenance_catalog_limit: int = 200
    """Короче этого (секунды) — подозрительно короткий файл."""
    maintenance_min_track_s: int = 10

    """Цепочка источников загрузки по умолчанию (порядок и выключенные можно поменять в приложении)."""
    sources_order: tuple[str, ...] = DEFAULT_SOURCES_ORDER
    sources_disabled: tuple[str, ...] = ()
    """Пауза между запросами к Яндекс Музыке (секунды, случайно в диапазоне)."""
    yandex_delay_min_s: float = 2.0
    yandex_delay_max_s: float = 3.0
    """«Яндекс: метаданные» по умолчанию (переключается в приложении)."""
    yandex_metadata: bool = True
    """Как часто проверять подписку Плюс."""
    yandex_check_interval_s: int = 24 * 3600
    """Прокси для YouTube (yt-dlp и ytmusicapi), например `socks5://vpn:1080`."""
    youtube_proxy: str | None = None
    """Обновлять yt-dlp автоматически (в `<DATA_DIR>/ytdlp`): при старте и раз в `ytdlp_update_interval_s`."""
    ytdlp_auto_update: bool = True
    ytdlp_update_interval_s: int = 24 * 3600
    """Файл короче этой доли длительности по каталогу — превью: удаляем, источник считается неудачным."""
    preview_ratio: float = 0.9
    """«Метки» Lidarr: недостающие альбомы отслеживаемых артистов сначала пробует цепочка bodrod."""
    lidarr_wanted_sync: bool = True
    lidarr_wanted_interval_s: int = 3600
    """Не найденный цепочкой альбом пробовать снова через столько дней."""
    lidarr_wanted_retry_days: int = 7
    lidarr_wanted_batch: int = 20
    """Массовая выгрузка из Яндекса: пауза между треками (секунды) — низкий приоритет."""
    export_delay_s: float = 5.0

    """Архив Яндекса для рекомендаций: пользователь Navidrome → токен его аккаунта Яндекс Музыки."""
    yandex_accounts: dict[str, str] = field(default_factory=dict)
    """Сырые ответы API: `<папка>/<пользователь>/<дата>/<тип>.json`. Пусто — `<DATA_DIR>/yandex-archive`."""
    yandex_archive_dir: str | None = None
    harvest_enabled: bool = True
    """Пауза между запросами харвестера к одному аккаунту (секунды)."""
    harvest_delay_s: float = 1.0
    """Сколько раз в сутки сэмплировать «Мою волну» и сколько пачек за прогон."""
    harvest_wave_runs_per_day: int = 3
    harvest_wave_batches: int = 5
    """Похожие артисты — для стольких любимых артистов пользователя."""
    harvest_top_artists: int = 200
    """Резерв: предзакачанные кандидаты (Navidrome не видит — `.ndignore`). Пусто — `<MUSIC_DIR>/_reserve`."""
    reserve_dir: str | None = None
    reserve_limit: int = 500
    """Сколько треков в резерв за один проход харвестера (проход — раз в 10 минут)."""
    reserve_batch: int = 20
    """Discovery-пул (виден Navidrome). Пусто — `<MUSIC_DIR>/_discovery`."""
    discovery_dir: str | None = None
    """Правила пула «на пробу» (задача 2 ТЗ рекомендаций)."""
    pool_size: int = 150
    pool_refill_below: int = 100
    """Promote: столько полных прослушиваний (не меньше этой доли длительности)."""
    promote_full_plays: int = 2
    full_play_ratio: float = 0.8
    """Reject: скип раньше стольких секунд — на такой раз; отклонённое не предлагается столько дней."""
    early_skip_s: int = 30
    reject_early_skips: int = 2
    reject_block_days: int = 180
    """Expire: столько дней без прослушиваний или столько показов в волне без запуска."""
    expire_days: int = 21
    expire_shown: int = 3
    """У артиста столько и больше reject — понижающий коэффициент."""
    artist_reject_threshold: int = 3
    """Движок кандидатов (задача 3): доля новых для пользователя артистов, полураспад веса семени, число семян."""
    engine_enabled: bool = True
    engine_new_artist_share: float = 0.5
    engine_seed_half_life_days: float = 90.0
    engine_max_seeds: int = 60
    """ListenBrainz: токен (LB Radio) и пользователи Navidrome → имя в LB (плейлисты Weekly Exploration / Jams)."""
    listenbrainz_token: str | None = None
    listenbrainz_users: dict[str, str] = field(default_factory=dict)
    """Чарты (задача 4): какие обновлять (пусто — все) и сколько верхних недостающих качать в пул."""
    charts: tuple[str, ...] = ()
    charts_download_top: int = 30

    @property
    def archive_path(self) -> str:
        return self.yandex_archive_dir or os.path.join(self.data_dir, "yandex-archive")

    @property
    def reserve_path(self) -> str:
        return self.reserve_dir or os.path.join(self.music_dir, "_reserve")

    @property
    def discovery_path(self) -> str:
        return self.discovery_dir or os.path.join(self.music_dir, "_discovery")

    @property
    def ytdlp_dir(self) -> str:
        return os.path.join(self.data_dir, "ytdlp")

    @property
    def staging_dir(self) -> str:
        """Временная папка для файлов Яндекса и YouTube до раскладки в библиотеку."""
        return os.path.join(self.data_dir, "staging")

    @property
    def slskd_enabled(self) -> bool:
        return bool(self.slskd_url and self.slskd_api_key)

    @property
    def lidarr_enabled(self) -> bool:
        return bool(self.lidarr_url and self.lidarr_api_key)

    @property
    def placement_enabled(self) -> bool:
        """Класть файлы в библиотеку самим. Без этих папок — прежнее поведение: ждать скан Navidrome."""
        return bool(self.slskd_downloads_dir and self.library_dir)

    @property
    def cleanup_enabled(self) -> bool:
        return bool(self.navidrome_db)

    def is_service_user(self, username: str) -> bool:
        name = username.lower()
        return name == self.navidrome_admin_user.lower() or name in (u.lower() for u in self.service_users)

    @property
    def quarantine_path(self) -> str:
        return self.quarantine_dir or os.path.join(self.music_dir, ".bodrod-quarantine")

    @property
    def db_path(self) -> str:
        return os.path.join(self.data_dir, "bodrod.sqlite3")

    @classmethod
    def from_env(cls) -> Settings:
        navidrome = _opt("NAVIDROME_URL")
        user = _opt("NAVIDROME_ADMIN_USER")
        password = _opt("NAVIDROME_ADMIN_PASSWORD", os.path.join(SECRETS_DIR, "navidrome_password"))
        if not (navidrome and user and password):
            raise RuntimeError("Нужно задать NAVIDROME_URL, NAVIDROME_ADMIN_USER и NAVIDROME_ADMIN_PASSWORD")
        formats = _opt("ALLOWED_FORMATS")
        delay_min, delay_max = _range("YANDEX_DELAY_S", (2.0, 3.0))
        quality = _opt("LIDARR_QUALITY_PROFILE_ID")
        metadata = _opt("LIDARR_METADATA_PROFILE_ID")
        return cls(
            navidrome_url=navidrome,
            navidrome_admin_user=user,
            navidrome_admin_password=password,
            slskd_url=_opt("SLSKD_URL"),
            slskd_api_key=_secret("SLSKD_API_KEY"),
            lidarr_url=_opt("LIDARR_URL"),
            lidarr_api_key=_secret("LIDARR_API_KEY"),
            lidarr_root_folder=_opt("LIDARR_ROOT_FOLDER"),
            lidarr_quality_profile_id=int(quality) if quality else None,
            lidarr_metadata_profile_id=int(metadata) if metadata else None,
            yandex_token=_secret("YANDEX_MUSIC_TOKEN", os.path.join(SECRETS_DIR, "yandex_music_token")),
            deezer_enabled=(_opt("DEEZER_ENABLED") or "true").lower() != "false",
            discover_providers=_list("DISCOVER_PROVIDERS", DEFAULT_DISCOVER_PROVIDERS),
            cover_providers=_list("COVER_PROVIDERS", DEFAULT_COVER_PROVIDERS),
            tag_providers=_list("TAG_PROVIDERS", DEFAULT_TAG_PROVIDERS),
            discogs_token=_secret("DISCOGS_TOKEN", os.path.join(SECRETS_DIR, "discogs_token")),
            lastfm_api_key=_secret("LASTFM_API_KEY", os.path.join(SECRETS_DIR, "lastfm_api_key")),
            data_dir=_opt("DATA_DIR") or "/data",
            slskd_search_timeout_s=_int("SLSKD_SEARCH_TIMEOUT_S", 25),
            slskd_max_candidates=_int("SLSKD_MAX_CANDIDATES", 5),
            slskd_parallel_searches=max(1, _int("SLSKD_PARALLEL_SEARCHES", 2)),
            slskd_search_gap_s=_int("SLSKD_SEARCH_GAP_S", 5),
            slskd_retry_interval_s=_int("SLSKD_RETRY_INTERVAL_S", 6 * 3600),
            slskd_retry_rounds=_int("SLSKD_RETRY_ROUNDS", 3),
            slskd_stall_timeout_s=_int("SLSKD_STALL_TIMEOUT_S", 20 * 60),
            import_timeout_s=_int("IMPORT_TIMEOUT_S", 15 * 60),
            allowed_formats=tuple(f.strip().lower() for f in formats.split(",")) if formats else DEFAULT_FORMATS,
            min_bitrate=_int("MIN_BITRATE", 192),
            slskd_downloads_dir=_opt("SLSKD_DOWNLOADS_DIR"),
            library_dir=_opt("LIBRARY_DIR"),
            library_layout=_opt("LIBRARY_LAYOUT") or "{artist}/{album}/{artist} - {title}",
            delete_source=(_opt("DELETE_SOURCE") or "true").lower() not in ("false", "0", "no"),
            navidrome_db=_opt("NAVIDROME_DB"),
            service_users=tuple(u.strip() for u in (_opt("SERVICE_USERS") or "").split(",") if u.strip()),
            navidrome_music_dir=_opt("NAVIDROME_MUSIC_DIR") or "/music",
            music_dir=_opt("MUSIC_DIR") or "/music",
            lidarr_music_dir=_opt("LIDARR_MUSIC_DIR"),
            blocklist_sweep_s=_int("BLOCKLIST_SWEEP_S", 15 * 60),
            slsk_worker_queue=_opt("SLSK_WORKER_QUEUE"),
            maintenance_enabled=_bool("MAINTENANCE_ENABLED", True),
            maintenance_at=_opt("MAINTENANCE_AT") or "04:00",
            maintenance_dry_run=_bool("MAINTENANCE_DRY_RUN", False),
            quarantine_dir=_opt("QUARANTINE_DIR"),
            quarantine_days=_int("QUARANTINE_DAYS", 30),
            maintenance_catalog_limit=_int("MAINTENANCE_CATALOG_LIMIT", 200),
            maintenance_min_track_s=_int("MAINTENANCE_MIN_TRACK_S", 10),
            sources_order=_list("SOURCES_ORDER", DEFAULT_SOURCES_ORDER),
            sources_disabled=_list("SOURCES_DISABLED", ()),
            yandex_delay_min_s=delay_min,
            yandex_delay_max_s=delay_max,
            yandex_metadata=_bool("YANDEX_METADATA", True),
            yandex_check_interval_s=_int("YANDEX_CHECK_INTERVAL_S", 24 * 3600),
            youtube_proxy=_opt("YOUTUBE_PROXY", os.path.join(SECRETS_DIR, "youtube_proxy")),
            ytdlp_auto_update=_bool("YTDLP_AUTO_UPDATE", True),
            ytdlp_update_interval_s=_int("YTDLP_UPDATE_INTERVAL_S", 24 * 3600),
            preview_ratio=float(_opt("PREVIEW_RATIO") or 0.9),
            lidarr_wanted_sync=_bool("LIDARR_WANTED_SYNC", True),
            lidarr_wanted_interval_s=_int("LIDARR_WANTED_INTERVAL_S", 3600),
            lidarr_wanted_retry_days=_int("LIDARR_WANTED_RETRY_DAYS", 7),
            lidarr_wanted_batch=_int("LIDARR_WANTED_BATCH", 20),
            export_delay_s=float(_opt("EXPORT_DELAY_S") or 5.0),
            yandex_accounts=yandex_accounts(),
            yandex_archive_dir=_opt("YANDEX_ARCHIVE_DIR"),
            harvest_enabled=_bool("HARVEST_ENABLED", True),
            harvest_delay_s=float(_opt("HARVEST_DELAY_S") or 1.0),
            harvest_wave_runs_per_day=max(1, _int("HARVEST_WAVE_RUNS_PER_DAY", 3)),
            harvest_wave_batches=max(1, _int("HARVEST_WAVE_BATCHES", 5)),
            harvest_top_artists=_int("HARVEST_TOP_ARTISTS", 200),
            reserve_dir=_opt("RESERVE_DIR"),
            reserve_limit=_int("RESERVE_LIMIT", 500),
            reserve_batch=_int("RESERVE_BATCH", 20),
            discovery_dir=_opt("DISCOVERY_DIR"),
            pool_size=_int("POOL_SIZE", 150),
            pool_refill_below=_int("POOL_REFILL_BELOW", 100),
            promote_full_plays=_int("POOL_PROMOTE_FULL_PLAYS", 2),
            full_play_ratio=float(_opt("POOL_FULL_PLAY_RATIO") or 0.8),
            early_skip_s=_int("POOL_EARLY_SKIP_S", 30),
            reject_early_skips=_int("POOL_REJECT_EARLY_SKIPS", 2),
            reject_block_days=_int("POOL_REJECT_BLOCK_DAYS", 180),
            expire_days=_int("POOL_EXPIRE_DAYS", 21),
            expire_shown=_int("POOL_EXPIRE_SHOWN", 3),
            artist_reject_threshold=_int("POOL_ARTIST_REJECT_THRESHOLD", 3),
            engine_enabled=_bool("ENGINE_ENABLED", True),
            engine_new_artist_share=float(_opt("ENGINE_NEW_ARTIST_SHARE") or 0.5),
            engine_seed_half_life_days=float(_opt("ENGINE_SEED_HALF_LIFE_DAYS") or 90),
            engine_max_seeds=_int("ENGINE_MAX_SEEDS", 60),
            listenbrainz_token=_secret("LISTENBRAINZ_TOKEN", os.path.join(SECRETS_DIR, "listenbrainz_token")),
            listenbrainz_users=_pairs("LISTENBRAINZ_USERS"),
            charts=_list("CHARTS", ()),
            charts_download_top=_int("CHARTS_DOWNLOAD_TOP", 30),
        )


def _pairs(name: str) -> dict[str, str]:
    """`a:x,b:y` → {a: x, b: y}."""
    result: dict[str, str] = {}
    for part in (_opt(name) or "").split(","):
        key, _, value = part.strip().partition(":")
        if key and value:
            result[key.strip()] = value.strip()
    return result


YANDEX_ACCOUNTS_DIR = os.path.join(SECRETS_DIR, "yandex_accounts")


def yandex_accounts(accounts_dir: str = YANDEX_ACCOUNTS_DIR) -> dict[str, str]:
    """Токены аккаунтов Яндекса для архива рекомендаций.

    `YANDEX_ACCOUNTS=nikolai:/secrets/ya_nikolai,olga:/secrets/ya_olga` (пользователь Navidrome : файл с токеном)
    или просто файлы `/secrets/yandex_accounts/<пользователь Navidrome>` с токеном внутри.
    """
    pairs: dict[str, str] = {}
    raw = _opt("YANDEX_ACCOUNTS")
    for part in (raw or "").split(","):
        user, _, path = part.strip().partition(":")
        if user and path:
            pairs[user.strip()] = path.strip()
    if not pairs and os.path.isdir(accounts_dir):
        for name in sorted(os.listdir(accounts_dir)):
            path = os.path.join(accounts_dir, name)
            if os.path.isfile(path) and not name.startswith("."):
                pairs[name] = path
    result: dict[str, str] = {}
    for user, path in pairs.items():
        with open(path, encoding="utf-8") as f:
            content = f.read().strip()
        token = _COMMENT.sub("", content.splitlines()[0]).strip().strip("\"'") if content else ""
        if not token.isascii():
            raise RuntimeError(f"Токен Яндекса для {user}: не-ASCII символы — проверьте файл {path}")
        if token:
            result[user] = token
    return result
