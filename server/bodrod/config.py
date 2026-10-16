"""Настройки сервиса из переменных окружения."""

from __future__ import annotations

import os
from dataclasses import dataclass, field

DEFAULT_FORMATS = ("flac", "mp3", "m4a", "ogg", "opus", "aac", "wav", "alac")


def _opt(name: str) -> str | None:
    value = os.environ.get(name, "").strip()
    return value or None


def _int(name: str, default: int) -> int:
    value = _opt(name)
    return int(value) if value else default


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

    data_dir: str = "/data"
    worker_interval_s: float = 3.0
    slskd_search_timeout_s: int = 25
    slskd_max_candidates: int = 5
    """Сколько ждать, пока пир начнёт отдавать файл (очередь у него), прежде чем пробовать следующий источник."""
    slskd_stall_timeout_s: int = 20 * 60
    import_timeout_s: int = 15 * 60
    lidarr_check_interval_s: int = 60
    lidarr_give_up_s: int = 24 * 3600
    allowed_formats: tuple[str, ...] = field(default=DEFAULT_FORMATS)
    min_bitrate: int = 192

    @property
    def slskd_enabled(self) -> bool:
        return bool(self.slskd_url and self.slskd_api_key)

    @property
    def lidarr_enabled(self) -> bool:
        return bool(self.lidarr_url and self.lidarr_api_key)

    @property
    def db_path(self) -> str:
        return os.path.join(self.data_dir, "bodrod.sqlite3")

    @classmethod
    def from_env(cls) -> Settings:
        navidrome = _opt("NAVIDROME_URL")
        user = _opt("NAVIDROME_ADMIN_USER")
        password = _opt("NAVIDROME_ADMIN_PASSWORD")
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
            slskd_api_key=_opt("SLSKD_API_KEY"),
            lidarr_url=_opt("LIDARR_URL"),
            lidarr_api_key=_opt("LIDARR_API_KEY"),
            lidarr_root_folder=_opt("LIDARR_ROOT_FOLDER"),
            lidarr_quality_profile_id=int(quality) if quality else None,
            lidarr_metadata_profile_id=int(metadata) if metadata else None,
            yandex_token=_opt("YANDEX_MUSIC_TOKEN"),
            deezer_enabled=(_opt("DEEZER_ENABLED") or "true").lower() != "false",
            data_dir=_opt("DATA_DIR") or "/data",
            slskd_search_timeout_s=_int("SLSKD_SEARCH_TIMEOUT_S", 25),
            slskd_max_candidates=_int("SLSKD_MAX_CANDIDATES", 5),
            slskd_stall_timeout_s=_int("SLSKD_STALL_TIMEOUT_S", 20 * 60),
            import_timeout_s=_int("IMPORT_TIMEOUT_S", 15 * 60),
            allowed_formats=tuple(f.strip().lower() for f in formats.split(",")) if formats else DEFAULT_FORMATS,
            min_bitrate=_int("MIN_BITRATE", 192),
        )
