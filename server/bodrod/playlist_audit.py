"""Отчёт администратору: чьи плейлисты на сервере и какие могли оказаться не у того владельца.

Subsonic API этого не показывает: администратору `getPlaylists` отдаёт все плейлисты (и чужие приватные),
обычному пользователю — свои и публичные. Поэтому отчёт строится по БД Navidrome.

Кто мог создать плейлист «не от того» аккаунта:
- импорт файлов `.m3u` / `.nsp` из библиотеки — Navidrome делает владельцем первого администратора, а видимость
  задаёт `ND_DEFAULTPLAYLISTPUBLICVISIBILITY` (по умолчанию приватные);
- выгрузка из Яндекса bodrod до 0.17.0 писала `.m3u8` в `Плейлисты Яндекса/` — та же история;
- Explo создаёт плейлисты учёткой из своего `.env` (если у второго экземпляра там админ — плейлисты админа).

Ничего не переносится и не удаляется: решение принимает владелец сервера.
"""

from __future__ import annotations

from .models import PlaylistAudit, PlaylistAuditEntry
from .navidrome_db import NavidromeDb, NdPlaylist
from .sources.export import PLAYLISTS_DIR


def _reasons(p: NdPlaylist) -> list[str]:
    path = (p.path or "").lower()
    name = p.name.lower()
    reasons: list[str] = []
    if p.owner is None:
        reasons.append("no_owner")
    if PLAYLISTS_DIR.lower() in path:
        reasons.append("yandex_export")
    elif p.path and p.smart:
        reasons.append("smart_file")
    elif p.path:
        reasons.append("imported_file")
    if "explo" in path or "explo" in name or name.startswith("weekly "):
        reasons.append("explo")
    return reasons


def build_audit(db: NavidromeDb) -> PlaylistAudit:
    users = db.users()
    admins = [u.username for u in users if u.is_admin]
    entries = [
        PlaylistAuditEntry(
            id=p.id,
            name=p.name,
            owner=p.owner,
            public=p.public,
            song_count=p.song_count,
            path=p.path,
            smart=p.smart,
            created_at=p.created_at,
            reasons=_reasons(p),
        )
        for p in db.playlists()
    ]
    by_owner: dict[str, int] = {}
    for e in entries:
        by_owner[e.owner or "—"] = by_owner.get(e.owner or "—", 0) + 1
    return PlaylistAudit(
        users=[u.username for u in users],
        admins=admins,
        by_owner=by_owner,
        playlists=entries,
        review=sum(1 for e in entries if e.reasons),
    )
