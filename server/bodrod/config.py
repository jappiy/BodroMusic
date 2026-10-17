"""Настройки сервиса из переменных окружения."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field

DEFAULT_FORMATS = ("flac", "mp3", "m4a", "ogg", "opus", "aac", "wav", "alac")


DEFAULT_COVER_PROVIDERS = ("yandex", "deezer", "itunes", "musicbrainz")
DEFAULT_TAG_PROVIDERS = ("musicbrainz", "yandex", "deezer")
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
    """Источники обложек и тегов для обслуживания, по порядку: yandex, deezer, itunes, musicbrainz, discogs."""
    cover_providers: tuple[str, ...] = DEFAULT_COVER_PROVIDERS
    tag_providers: tuple[str, ...] = DEFAULT_TAG_PROVIDERS
    """Токен Discogs (необязательно): без него Discogs в цепочке пропускается."""
    discogs_token: str | None = None

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
            yandex_token=_secret("YANDEX_MUSIC_TOKEN"),
            deezer_enabled=(_opt("DEEZER_ENABLED") or "true").lower() != "false",
            cover_providers=_list("COVER_PROVIDERS", DEFAULT_COVER_PROVIDERS),
            tag_providers=_list("TAG_PROVIDERS", DEFAULT_TAG_PROVIDERS),
            discogs_token=_secret("DISCOGS_TOKEN", os.path.join(SECRETS_DIR, "discogs_token")),
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
        )
