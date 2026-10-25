"""Подводки к трекам на главной («✦ …» под треком): короткая живая строка о том, почему этот трек интересен.

Три слоя фактов:
- **Постоянные** (ночью, с кэшем в таблице `blurbs`): звучание по анализу (темп, энергия, настроение, «самый
  энергичный трек альбома»), место в альбоме, год и юбилей альбома, теги и одно предложение из Википедии /
  Яндекса / Last.fm ([ArtistFacts]), слушатели на Last.fm. Пересчёт — когда меняется хеш фактов.
- **Личные** (при запросе, по учётке): сколько раз слушали, давно не звучал, первое знакомство с исполнителем,
  «в общей библиотеке его любят» (только факт, без имён).
- **Причина в волне** (при запросе): «похоже на X, который вы лайкнули», «часто слушают вместе с Y»,
  «из чарта Deezer за неделю» — по пулу «На пробу» (`pool_owners.source`, `pool_reasons`, рёбра архива Яндекса).

Генератор — русские шаблоны (больше 30) по типам фактов, вес — «интересность». В одной волне тип подводки не
повторяется чаще раза в [TYPE_GAP] трека; у трека 2–3 варианта по очереди по дням. Длина ≤ [MAX_CHARS].
«Трек из альбома X» — только если фактов нет совсем и это не сингл.

Опционально ИИ (`BLURB_LLM_PROVIDER`: `anthropic` / `ollama`): ночью один раз на трек живой текст строго по
собранным фактам, с кэшем по хешу фактов; при ошибке — шаблоны.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import hashlib
import json
import logging
import re
import time
from collections import defaultdict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

import httpx

from ..db import Store, now_ms
from ..metadata.facts import GENRES_RU, is_single
from ..navidrome_db import NavidromeDb, NdAnnotation, NdSong
from .traits import traits

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS blurbs (
    track_id TEXT PRIMARY KEY,
    texts TEXT NOT NULL,
    facts_hash TEXT NOT NULL,
    generated_at INTEGER NOT NULL,
    source TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pool_reasons (
    username TEXT NOT NULL,
    key TEXT NOT NULL,
    source TEXT NOT NULL,
    seed TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (username, key)
);
"""

MAX_CHARS = 90
TYPE_GAP = 5
STALE_MS = 30 * 24 * 3600 * 1000
NIGHTLY_BATCH = 200
LONG_AGO_DAYS = 120
MANY_PLAYS = 10
ROTATE_SHARE = 0.8

WEIGHTS = {
    "context": 1.0,
    "llm": 0.95,
    "story": 0.9,
    "personal": 0.8,
    "album_energy": 0.75,
    "anniversary": 0.7,
    "position": 0.6,
    "sound": 0.55,
    "listeners": 0.4,
    "tags": 0.35,
    "year": 0.3,
    "album": 0.05,
}

_MONTHS = ("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября",
           "ноября", "декабря")  # fmt: skip
_SEASONS = {12: "зимы", 1: "зимы", 2: "зимы", 3: "весны", 4: "весны", 5: "весны", 6: "лета", 7: "лета", 8: "лета",
            9: "осени", 10: "осени", 11: "осени"}  # fmt: skip
_CHART_NAMES = {"deezer": "Deezer", "lastfm": "Last.fm", "yandex": "Яндекс Музыки"}

# region Шаблоны (по типу факта; {…} — поля факта)

TEMPLATES: dict[str, list[str]] = {
    "sound_slow_dark": ["Медленный и тёмный, {bpm} BPM", "Неторопливый и мрачный — {bpm} ударов в минуту"],
    "sound_slow_calm": ["Спокойный, {bpm} BPM — можно выдохнуть", "Неспешные {bpm} ударов в минуту"],
    "sound_fast": ["Быстрый и напористый — {bpm} BPM", "{bpm} ударов в минуту: держите темп"],
    "sound_dance": ["Танцевальный, {bpm} BPM", "Под него сложно усидеть на месте: {bpm} BPM"],
    "sound_happy": ["Светлый и лёгкий по настроению", "Солнечный трек — настроение вверх"],
    "sound_sad": ["Грустный и задумчивый", "Немного меланхолии в волне"],
    "sound_instrumental": ["Почти без слов — только музыка", "Инструментальный: голос здесь не нужен"],
    "sound_acoustic": ["Живое акустическое звучание", "Акустика без электроники"],
    "album_energy_max": ["Самый энергичный трек альбома «{album}»", "Самый мощный момент «{album}»"],
    "album_energy_min": ["Самый спокойный трек альбома «{album}»", "Самая тихая страница «{album}»"],
    "position_first": ["Открывающий трек альбома «{album}»", "Им начинается «{album}»"],
    "position_last": ["Закрывает альбом «{album}»", "Финальный трек «{album}»"],
    "anniversary": ["Альбому «{album}» в этом году {years}", "«{album}» вышел {years} назад, в {year}-м"],
    "year_old": ["Записан в {year} году", "Привет из {decade}-х: {year} год"],
    "year_new": ["Свежий: вышел в {year} году", "Новинка {year} года"],
    "tags": ["Жанр — {genres}", "{Genres}"],
    "listeners": ["У {artist} больше {listeners} слушателей на Last.fm"],
    "plays_many": ["Вы слушали его {plays}", "Один из ваших любимых: {plays}"],
    "plays_never": ["Вы ещё ни разу его не слушали", "Для вас — впервые"],
    "long_ago": ["Вы не слушали его с {since}", "Давно не звучал у вас — с {since}"],
    "first_artist": ["Первое знакомство с {artist}", "Раньше вы не слушали {artist}"],
    "shared_love": ["В общей библиотеке этот трек любят", "Его любят в вашей общей библиотеке"],
    "similar_liked": ["Похоже на «{seed}», который вы лайкнули", "Если нравится «{seed}» — вам сюда"],
    "lastfm_similar": ["Часто слушают вместе с {seed}", "Last.fm: рядом с {seed}"],
    "deezer_similar": ["Deezer советует тем, кто слушает {seed}", "Похоже на {seed} — по данным Deezer"],
    "listenbrainz": ["Слушатели {seed} любят и этот трек", "ListenBrainz: из круга {seed}"],
    "chart": ["Из чарта {chart} за неделю", "Сейчас в чарте {chart}"],
    "discovery": ["Новое для вас — из пула «На пробу»", "Незнакомый трек: вдруг зайдёт"],
    "album": ["Трек из альбома «{album}»"],
}
"""Тип шаблона → группа подводки (для антиповтора и веса)."""
GROUP = {
    **dict.fromkeys(
        (
            "sound_slow_dark",
            "sound_slow_calm",
            "sound_fast",
            "sound_dance",
            "sound_happy",
            "sound_sad",
            "sound_instrumental",
            "sound_acoustic",
        ),
        "sound",
    ),  # fmt: skip
    "album_energy_max": "album_energy",
    "album_energy_min": "album_energy",
    "position_first": "position",
    "position_last": "position",
    "anniversary": "anniversary",
    "year_old": "year",
    "year_new": "year",
    "tags": "tags",
    "listeners": "listeners",
    **dict.fromkeys(("plays_many", "plays_never", "long_ago", "first_artist", "shared_love"), "personal"),
    **dict.fromkeys(
        ("similar_liked", "lastfm_similar", "deezer_similar", "listenbrainz", "chart", "discovery"), "context"
    ),  # fmt: skip
    "album": "album",
}

# endregion


@dataclass
class Fact:
    kind: str
    """Тип шаблона из [TEMPLATES] или `story` / `llm` (готовый текст в `text`)."""
    data: dict[str, Any] = field(default_factory=dict)
    text: str | None = None

    @property
    def group(self) -> str:
        return self.kind if self.kind in ("story", "llm") else GROUP[self.kind]


@dataclass
class Variant:
    group: str
    text: str
    weight: float


def plural(n: int, one: str, few: str, many: str) -> str:
    tail = n % 100
    if 11 <= tail <= 14:
        word = many
    elif n % 10 == 1:
        word = one
    elif 2 <= n % 10 <= 4:
        word = few
    else:
        word = many
    return f"{n} {word}"


def render(fact: Fact) -> list[str]:
    """Все формулировки факта, не длиннее [MAX_CHARS]."""
    if fact.text is not None:
        return [fact.text] if len(fact.text) <= MAX_CHARS else []
    result = []
    for template in TEMPLATES[fact.kind]:
        try:
            text = template.format(**fact.data)
        except (KeyError, IndexError):
            continue
        if len(text) <= MAX_CHARS:
            result.append(text)
    return result


def variants(facts: list[Fact]) -> list[Variant]:
    out: list[Variant] = []
    for fact in facts:
        out.extend(Variant(fact.group, text, WEIGHTS[fact.group]) for text in render(fact))
    return out


# region Факты


def static_facts(
    song: NdSong,
    feature: dict[str, Any] | None,
    album_energy: tuple[str | None, str | None],
    album_size: int,
    info: dict[str, Any] | None,
    today: dt.date,
) -> list[Fact]:
    """Факты, не зависящие от слушателя: звучание, альбом, год, Last.fm / Википедия."""
    facts: list[Fact] = []
    single = is_single(song.title, song.album)
    album = song.album or ""
    t = traits(feature, song.title, song.artist, song.album) if feature else {}
    bpm = round(t["bpm"]) if t.get("bpm") else None
    energy = t.get("energy")
    if t:
        valence = t.get("valence")
        if t.get("instrumental") is not None and t["instrumental"] >= 0.8:
            facts.append(Fact("sound_instrumental"))
        if bpm and bpm <= 85 and valence is not None and valence < 0.4:
            facts.append(Fact("sound_slow_dark", {"bpm": bpm}))
        elif bpm and bpm <= 90 and (t.get("relaxed") or 0) > 0.5:
            facts.append(Fact("sound_slow_calm", {"bpm": bpm}))
        elif bpm and (t.get("danceability") or 0) > 0.7 and 110 <= bpm <= 135:
            facts.append(Fact("sound_dance", {"bpm": bpm}))
        elif bpm and bpm >= 140 and energy is not None and energy > 0.6:
            facts.append(Fact("sound_fast", {"bpm": bpm}))
        if (t.get("happy") or 0) > 0.7:
            facts.append(Fact("sound_happy"))
        elif (t.get("sad") or 0) > 0.7:
            facts.append(Fact("sound_sad"))
        if (t.get("acoustic") or 0) > 0.75:
            facts.append(Fact("sound_acoustic"))
    if not single and album_size >= 4:
        if album_energy[0] == song.id:
            facts.append(Fact("album_energy_max", {"album": album}))
        elif album_energy[1] == song.id:
            facts.append(Fact("album_energy_min", {"album": album}))
        if song.track_number == 1 and (song.disc_number or 1) == 1:
            facts.append(Fact("position_first", {"album": album}))
        elif song.track_number and song.track_number == album_size:
            facts.append(Fact("position_last", {"album": album}))
    if song.year:
        age = today.year - song.year
        if not single and age >= 10 and age % 5 == 0:
            facts.append(
                Fact("anniversary", {"album": album, "year": song.year, "years": plural(age, "год", "года", "лет")})
            )
        elif age >= 15:
            facts.append(Fact("year_old", {"year": song.year, "decade": song.year // 10 * 10 % 100 or "2000"}))
        elif age <= 1:
            facts.append(Fact("year_new", {"year": song.year}))
    info = info or {}
    for text, source in zip(info.get("variants") or [], info.get("sources") or [], strict=False):
        if source != "template":
            facts.append(Fact("story", text=text.rstrip(".")))
            break
    genres = [GENRES_RU[g.lower()] for g in info.get("tags") or [] if g.lower() in GENRES_RU][:2]
    if genres:
        joined = " и ".join(genres)
        facts.append(Fact("tags", {"genres": joined, "Genres": joined[:1].upper() + joined[1:]}))
    listeners = info.get("listeners")
    if listeners and listeners >= 10_000:
        facts.append(Fact("listeners", {"artist": song.artist, "listeners": _count(listeners)}))
    return facts


def _count(n: int) -> str:
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}".rstrip("0").rstrip(".").replace(".", ",") + " млн"
    return f"{n // 1000} тыс."


def _parse_date(raw: str | None) -> dt.datetime | None:
    if not raw:
        return None
    try:
        return dt.datetime.fromisoformat(str(raw).replace("Z", "+00:00").split(".")[0].replace(" ", "T"))
    except ValueError:
        return None


def personal_facts(
    song: NdSong, username: str, notes: list[NdAnnotation], artist_plays: int, today: dt.date
) -> list[Fact]:
    """Личная история учётки. О других — только «в общей библиотеке любят», без имён и подробностей."""
    facts: list[Fact] = []
    mine = next((n for n in notes if n.username == username), None)
    plays = mine.play_count if mine else 0
    others_love = any(n.username != username and (n.starred or n.rating >= 4) for n in notes)
    if plays >= MANY_PLAYS:
        facts.append(Fact("plays_many", {"plays": plural(plays, "раз", "раза", "раз")}))
    last = _parse_date(mine.play_date) if mine else None
    days = (today - last.date()).days if last is not None else 0
    if plays > 0 and days >= LONG_AGO_DAYS:
        # До ~10 месяцев — «с весны», дальше — «с марта 2025».
        since = _SEASONS[last.month] if days < 300 else _month_since(last)
        facts.append(Fact("long_ago", {"since": since}))
    if artist_plays == 0:
        facts.append(Fact("first_artist", {"artist": song.artist}))
    elif plays == 0:
        facts.append(Fact("plays_never"))
    if others_love and not (mine and mine.starred):
        facts.append(Fact("shared_love"))
    return facts


def _month_since(when: dt.datetime) -> str:
    """«марта 2025» → используется как «с марта 2025»."""
    return f"{_MONTHS[when.month - 1]} {when.year}"


def context_facts(source: str | None, seed: str | None, liked_seed: bool) -> list[Fact]:
    """Почему трек попал в волну (трек из пула «На пробу»)."""
    if not source:
        return []
    sources = source.split(",")
    if source.startswith("chart:"):
        chart = source.removeprefix("chart:").split(":")[0]
        return [Fact("chart", {"chart": _CHART_NAMES.get(chart, chart)})]
    if seed and liked_seed:
        return [Fact("similar_liked", {"seed": seed})]
    if seed and "lastfm" in sources:
        return [Fact("lastfm_similar", {"seed": seed})]
    if seed and "deezer" in sources:
        return [Fact("deezer_similar", {"seed": seed})]
    if seed and "listenbrainz" in sources:
        return [Fact("listenbrainz", {"seed": seed})]
    return [Fact("discovery")]


# endregion


def choose(options: dict[str, list[Variant]], order: list[str], day: int) -> dict[str, Variant]:
    """Подводка для каждого трека волны по порядку: тип не чаще раза в [TYPE_GAP] треков; у трека — до трёх
    лучших вариантов по очереди по дням."""
    result: dict[str, Variant] = {}
    recent: list[str] = []
    for track_id in order:
        items = sorted(options.get(track_id) or [], key=lambda v: -v.weight)
        if not items:
            recent.append("")
            continue
        fresh = [v for v in items if v.group not in recent[-(TYPE_GAP - 1) :]] or items
        top = [v for v in fresh if v.group == fresh[0].group][:3]
        if len(top) < 2:  # у лучшего факта одна формулировка — чередуем с близким по интересности
            top = [v for v in fresh[:2] if v.weight >= fresh[0].weight * ROTATE_SHARE]
        seed = int(hashlib.md5(f"{track_id}:{day}".encode()).hexdigest()[:8], 16)  # noqa: S324 — не криптография
        pick = top[seed % len(top)]
        result[track_id] = pick
        recent.append(pick.group)
    return result


# region ИИ (опционально)

LLM_SYSTEM = (
    "Ты пишешь одну короткую подводку к песне для музыкального приложения: живая фраза по-русски, без канцелярита, "
    f"не длиннее {MAX_CHARS} символов, одна мысль, без кавычек вокруг всей фразы, без эмодзи и без точки в конце. "
    "Используй только переданные факты, ничего не придумывай. Если факты скучные — выбери самый интересный."
)


class LlmWriter:
    """Живой текст по фактам: `anthropic` (официальный SDK, ставится отдельно) или `ollama` (HTTP)."""

    def __init__(
        self, provider: str, *, http: httpx.AsyncClient, model: str | None, api_key: str | None, url: str | None
    ):
        self.provider = provider
        self._http = http
        self._model = model
        self._api_key = api_key
        self._url = (url or "http://ollama:11434").rstrip("/")
        self._client: Any = None

    async def write(self, song: NdSong, facts: list[str]) -> str | None:
        prompt = f"Исполнитель: {song.artist}\nТрек: {song.title}\nФакты:\n" + "\n".join(f"- {f}" for f in facts)
        try:
            text = await (self._anthropic(prompt) if self.provider == "anthropic" else self._ollama(prompt))
        except Exception as e:  # noqa: BLE001 — при ошибке остаются шаблоны
            log.info("Подводки: ИИ (%s) не ответил: %s", self.provider, e)
            return None
        return clean_llm(text)

    async def _anthropic(self, prompt: str) -> str | None:
        if self._client is None:
            import anthropic  # необязательная зависимость: pip install anthropic

            self._client = (
                anthropic.AsyncAnthropic(api_key=self._api_key) if self._api_key else anthropic.AsyncAnthropic()
            )
        response = await self._client.beta.messages.create(
            model=self._model or "claude-opus-5-5",
            max_tokens=1024,
            system=LLM_SYSTEM,
            messages=[{"role": "user", "content": prompt}],
            output_config={"effort": "low"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
        if response.stop_reason == "refusal":
            return None
        return next((b.text for b in response.content if b.type == "text"), None)

    async def _ollama(self, prompt: str) -> str | None:
        response = await self._http.post(
            f"{self._url}/api/generate",
            json={"model": self._model or "qwen2.5:7b", "system": LLM_SYSTEM, "prompt": prompt, "stream": False},
            timeout=120,
        )
        response.raise_for_status()
        return (response.json() or {}).get("response")


_CYR = re.compile(r"[а-яё]", re.IGNORECASE)


def clean_llm(text: str | None) -> str | None:
    """Одна строка по-русски, без кавычек вокруг и точки в конце, не длиннее лимита — иначе не берём."""
    if not text:
        return None
    line = " ".join(text.strip().splitlines()[0].split()).strip("«»\"' ").rstrip(".")
    if not line or len(line) > MAX_CHARS or len(_CYR.findall(line)) < len(line) // 3:
        return None
    return line


# endregion


class BlurbService:
    def __init__(
        self,
        store: Store,
        db: NavidromeDb,
        *,
        features: Callable[[list[str]], Awaitable[dict[str, dict[str, Any]]]] | None = None,
        info: Callable[[NdSong], Awaitable[dict[str, Any] | None]] | None = None,
        llm: LlmWriter | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._store = store
        self._db = db
        self._features = features
        self._info = info
        self._llm = llm
        self._clock = clock
        self._lock = asyncio.Lock()
        store.executescript(SCHEMA)

    # region Причины для пула

    def remember_reason(self, username: str, key: str, source: str | None, seed: str | None) -> None:
        """Движок кандидатов: откуда трек и на какое «семя» он похож (для подводки «похоже на X»)."""
        self._store.execute(
            "INSERT INTO pool_reasons (username, key, source, seed, created_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(username, key) DO UPDATE SET source = excluded.source, seed = excluded.seed",
            (username, key, source or "", seed, now_ms()),
        )

    def _reasons(self, username: str, song_ids: list[str]) -> dict[str, tuple[str | None, str | None, bool]]:
        if not song_ids:
            return {}
        marks = ",".join("?" for _ in song_ids)
        rows = self._store.query(
            f"SELECT t.song_id, t.key, t.yandex_id, o.source, r.seed FROM pool_tracks t "
            f"JOIN pool_owners o ON o.track_id = t.id AND o.username = ? "
            f"LEFT JOIN pool_reasons r ON r.username = o.username AND r.key = t.key "
            f"WHERE t.song_id IN ({marks})",
            [username, *song_ids],
        )
        result: dict[str, tuple[str | None, str | None, bool]] = {}
        for r in rows:
            seed, liked = r["seed"], False
            if r["yandex_id"]:
                edge = self._store.query_one(
                    "SELECT y.title, y.artists, l.value AS liked FROM reco_edges e JOIN y_tracks y ON y.id = e.seed_id "
                    "LEFT JOIN y_likes l ON l.kind = 'track' AND l.item_id = e.seed_id AND l.value > 0 "
                    "WHERE e.cand_id = ? ORDER BY e.rank LIMIT 1",
                    (r["yandex_id"],),
                )
                if edge is not None:
                    seed, liked = edge["title"], bool(edge["liked"])
            result[r["song_id"]] = (r["source"], seed, liked)
        return result

    # endregion

    async def for_user(self, username: str, song_ids: list[str], today: dt.date | None = None) -> dict[str, Variant]:
        """Подводки для треков волны (в порядке волны): постоянные факты из кэша, личные и причина — сейчас."""
        today = today or dt.date.today()
        ids = list(dict.fromkeys(song_ids))[-50:]
        songs = {s.id: s for s in await asyncio.to_thread(self._db.songs, ids)}
        notes = await asyncio.to_thread(self._db.annotations, list(songs))
        by_song: dict[str, list[NdAnnotation]] = defaultdict(list)
        for n in notes:
            by_song[n.item_id].append(n)
        artist_plays = await asyncio.to_thread(
            self._artist_plays, username, {s.artist_id for s in songs.values() if s.artist_id}
        )
        reasons = self._reasons(username, list(songs))
        cached = self._cached(list(songs))
        missing = [i for i in songs if i not in cached]
        if missing:
            computed = await self._compute(missing, with_network=False)
            cached.update(computed)
        options: dict[str, list[Variant]] = {}
        for song_id, song in songs.items():
            facts: list[Fact] = []
            source, seed, liked = reasons.get(song_id, (None, None, False))
            facts += context_facts(source, seed, liked)
            facts += personal_facts(
                song, username, by_song.get(song_id, []), artist_plays.get(song.artist_id or "", 1), today
            )
            found = variants(facts) + cached.get(song_id, [])
            if not found and not is_single(song.title, song.album):
                found = variants([Fact("album", {"album": song.album})])
            options[song_id] = found
        return choose(options, [i for i in ids if i in songs], today.toordinal())

    def _artist_plays(self, username: str, artist_ids: set[str]) -> dict[str, int]:
        if not artist_ids:
            return {}
        result: dict[str, int] = dict.fromkeys(artist_ids, 0)
        notes = {n.item_id: n.play_count for n in self._db.user_annotations(username) if n.play_count}
        if not notes:
            return result
        for song in self._db.songs(list(notes)):
            if song.artist_id in result:
                result[song.artist_id] += notes.get(song.id, 0)
        return result

    def _cached(self, song_ids: list[str]) -> dict[str, list[Variant]]:
        if not song_ids:
            return {}
        marks = ",".join("?" for _ in song_ids)
        rows = self._store.query(f"SELECT track_id, texts FROM blurbs WHERE track_id IN ({marks})", song_ids)
        return {r["track_id"]: [Variant(**v) for v in json.loads(r["texts"])] for r in rows}

    async def _compute(self, song_ids: list[str], with_network: bool) -> dict[str, list[Variant]]:
        """Постоянные факты → варианты; сохраняются, если изменился хеш фактов."""
        songs = await asyncio.to_thread(self._db.songs, song_ids)
        album_ids = {s.album_id for s in songs if s.album_id}
        album_songs = await asyncio.to_thread(self._album_songs, album_ids)
        all_ids = [s.id for group in album_songs.values() for s in group] + [s.id for s in songs]
        features = await self._features(list(dict.fromkeys(all_ids))) if self._features else {}
        today = dt.date.fromtimestamp(self._clock())
        result: dict[str, list[Variant]] = {}
        for song in songs:
            group = album_songs.get(song.album_id or "", [])
            energies = [
                (traits(features[s.id], s.title, s.artist).get("energy"), s.id) for s in group if s.id in features
            ]
            energies = [(e, i) for e, i in energies if e is not None]
            extremes = (max(energies)[1], min(energies)[1]) if len(energies) >= 4 else (None, None)
            info = None
            if with_network and self._info is not None:
                try:
                    info = await self._info(song)
                except Exception as e:  # noqa: BLE001 — без Last.fm / Википедии остаются остальные факты
                    log.info("Подводки: факты Last.fm для %s — %s: %s", song.artist, song.title, e)
            facts = static_facts(song, features.get(song.id), extremes, len(group), info, today)
            facts_hash = hashlib.sha1(
                json.dumps(
                    [(f.kind, f.data, f.text) for f in facts], ensure_ascii=False, sort_keys=True, default=str
                ).encode()
            ).hexdigest()  # noqa: S324
            row = self._store.query_one("SELECT facts_hash, texts, source FROM blurbs WHERE track_id = ?", (song.id,))
            if row is not None and row["facts_hash"] == facts_hash and (not with_network or row["source"] != "quick"):
                result[song.id] = [Variant(**v) for v in json.loads(row["texts"])]
                continue
            found = variants(facts)
            source = "templates" if with_network else "quick"
            if with_network and self._llm is not None and facts:
                text = await self._llm.write(song, [v.text for v in found])
                if text:
                    found.insert(0, Variant("llm", text, WEIGHTS["llm"]))
                    source = self._llm.provider
            if with_network or row is None:
                self._store.execute(
                    "INSERT INTO blurbs (track_id, texts, facts_hash, generated_at, source) VALUES (?, ?, ?, ?, ?) "
                    "ON CONFLICT(track_id) DO UPDATE SET texts = excluded.texts, facts_hash = excluded.facts_hash, "
                    "generated_at = excluded.generated_at, source = excluded.source",
                    (
                        song.id,
                        json.dumps([v.__dict__ for v in found], ensure_ascii=False),
                        facts_hash,
                        now_ms(),
                        source,
                    ),
                )
            result[song.id] = found
        return result

    def _album_songs(self, album_ids: set[str]) -> dict[str, list[NdSong]]:
        if not album_ids:
            return {}
        groups: dict[str, list[NdSong]] = defaultdict(list)
        for song in self._db.album_songs(sorted(album_ids)):
            groups[song.album_id or ""].append(song)
        return groups

    # region Ночной пересчёт

    async def run_forever(self, interval_s: float = 3600) -> None:
        """Раз в час — пачка треков без подводки или с устаревшей (сначала новые)."""
        while True:
            try:
                await self.refresh(NIGHTLY_BATCH)
            except Exception:  # noqa: BLE001 — повторим через час
                log.exception("Подводки: ошибка пересчёта")
            await asyncio.sleep(interval_s)

    async def refresh(self, limit: int) -> int:
        async with self._lock:
            rows = {r["track_id"]: r for r in self._store.query("SELECT track_id, generated_at, source FROM blurbs")}
            stale_before = now_ms() - STALE_MS
            ids = await asyncio.to_thread(lambda: [s.id for s in self._db.iter_songs()])
            todo = [i for i in ids if i not in rows or rows[i]["source"] == "quick"]
            todo += [
                i for i in ids if i in rows and rows[i]["source"] != "quick" and rows[i]["generated_at"] < stale_before
            ]
            done = 0
            for start in range(0, min(len(todo), limit), 20):
                chunk = todo[start : min(start + 20, limit)]
                await self._compute(chunk, with_network=True)
                done += len(chunk)
            if done:
                log.info("Подводки: обновлено %s (осталось %s)", done, max(0, len(todo) - done))
            return done

    # endregion
