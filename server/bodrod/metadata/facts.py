"""Короткий текст о треке или исполнителе для главной «Моя волна»: одна законченная фраза на русском.

Источники по порядку:
1. ru.wikipedia — статья о песне (если есть) и вступление статьи об исполнителе;
2. описание исполнителя в Яндекс Музыке;
3. русская биография Last.fm (`artist.getInfo`, `lang=ru`);
4. шаблонные фразы по метаданным и Last.fm: «Трек из альбома «Meliora» (2015)», «Жанр — пост-панк»,
   «У Ghost больше 1,2 млн слушателей на Last.fm».

Из описания берётся одно законченное предложение, которое целиком помещается в лимит (`max_chars`, по умолчанию
110 символов): без скобок с датами и транскрипциями, без многоточий и обрезки. Длинное предложение-определение
(«X — шведская рок-группа, основанная…») сокращается по запятой до законченной фразы. Английский текст не берётся.

Ответ — `fact` (лучший вариант) и `variants` (все подходящие, от лучшего к запасным): приложение показывает первый,
который помещается в отведённую высоту при текущем размере шрифта; не поместился ни один — блок не показывается.
Результаты кэшируются: в памяти на сутки и в базе bodrod на 30 дней; сбой источника не кэшируется.
"""

from __future__ import annotations

import hashlib
import html
import json
import logging
import re
import time
from collections.abc import Callable
from typing import Any

import httpx

from ..db import Store
from ..text import normalize
from .lastfm import LastFmClient, LastFmError

log = logging.getLogger(__name__)

CACHE_TTL_S = 24 * 3600
EMPTY_TTL_S = 6 * 3600
STORE_TTL_MS = 30 * 24 * 3600 * 1000
CACHE_MAX = 2000
DEFAULT_MAX_CHARS = 110
MIN_CHARS = 20
MAX_TAGS = 5
MAX_VARIANTS = 4
WIKI_API = "https://ru.wikipedia.org/w/api.php"
WIKI_TIMEOUT_S = 4.0
WIKI_PAUSE_S = 30 * 60
"""После сбоя Википедия не опрашивается полчаса: из сети NAS она может быть недоступна, ответ не должен ждать."""
"""Служебные теги Last.fm, которые ничего не говорят о музыке."""
_NOISE_TAGS = {"seen live", "favorites", "favourite", "favorite", "my favorite", "love", "awesome", "beautiful"}
_LINK = re.compile(r"<a [^>]*>.*?</a>", re.IGNORECASE | re.DOTALL)
_TAG = re.compile(r"<[^>]+>")
_PARENS = re.compile(r"\s*[\(\[][^\(\)\[\]]*[\)\]]")
_SPACE_BEFORE_PUNCT = re.compile(r"\s+([,.;:!?])")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
_CYR = re.compile(r"[а-яёА-ЯЁ]")
_LAT = re.compile(r"[a-zA-Z]")
_ABBREVIATIONS = {
    "г", "гг", "т", "е", "др", "англ", "нем", "фр", "исп", "итал", "род", "ок", "им", "св", "н", "э", "в", "вв",
    "см", "тыс", "млн", "млрд", "ул", "пр", "проч", "наст", "урожд", "букв", "т.е", "т.д", "т.п",
}  # fmt: skip
_WEAK_START = re.compile(
    r"^(?:однако|но|также|кроме того|при этом|позже|впоследствии|затем|поэтому|он|она|они|оно|это|этот|эта|там|"
    r"тогда|в нём|в ней|в них|его|её|их)\b",
    re.IGNORECASE,
)
# Продолжение после запятой, которое можно отбросить, не ломая фразу: придаточное, причастный оборот, «однако…».
_DROPPABLE_TAIL = re.compile(
    r"^(?:однако|но|а|и|который|которая|которое|которые|где|когда|хотя|после|основанн\w*|известн\w*|"
    r"один|одна|одно|одни|входящ\w*|ставш\w*|получивш\w*|выпущенн\w*|вышедш\w*|записанн\w*|созданн\w*|"
    r"работающ\w*|играющ\w*|исполняющ\w*|лидер\w*|автор\w*|солист\w*)\b",
    re.IGNORECASE,
)
_MUSIC_WORDS = re.compile(
    r"\b(?:групп\w*|певе?ц\w*|певиц\w*|музыкант\w*|рэпер\w*|исполнител\w*|композитор\w*|коллектив\w*|"
    r"дуэт\w*|диджей\w*|ансамбл\w*|альбом\w*|песн\w*|сингл\w*|композици\w*|трек\w*|гитарист\w*|вокалист\w*|"
    r"рок|поп|джаз\w*|хип-хоп\w*|оркестр\w*|бард\w*|автор-исполнитель)",
    re.IGNORECASE,
)
_SONG_WORDS = re.compile(r"\b(?:песн\w*|сингл\w*|композици\w*|трек\w*)", re.IGNORECASE)
LOOSE_ALBUMS = {"", "singles", "single", "синглы", "unknown album", "треки", "неизвестный альбом"}

GENRES_RU = {
    "rock": "рок", "alternative rock": "альтернативный рок", "alternative": "альтернатива", "indie": "инди",
    "indie rock": "инди-рок", "indie pop": "инди-поп", "pop": "поп", "pop rock": "поп-рок", "punk": "панк",
    "punk rock": "панк-рок", "post-punk": "пост-панк", "metal": "метал", "heavy metal": "хеви-метал",
    "hard rock": "хард-рок", "progressive rock": "прогрессив-рок", "psychedelic rock": "психоделический рок",
    "classic rock": "классический рок", "blues": "блюз", "blues rock": "блюз-рок", "jazz": "джаз", "soul": "соул",
    "funk": "фанк", "rnb": "ритм-н-блюз", "r&b": "ритм-н-блюз", "hip-hop": "хип-хоп", "hip hop": "хип-хоп",
    "rap": "рэп", "russian rap": "русский рэп", "electronic": "электроника", "electronica": "электроника",
    "techno": "техно", "house": "хаус", "trance": "транс", "ambient": "эмбиент", "dance": "танцевальная музыка",
    "disco": "диско", "synthpop": "синти-поп", "new wave": "новая волна", "folk": "фолк", "folk rock": "фолк-рок",
    "country": "кантри", "reggae": "регги", "ska": "ска", "classical": "классика", "soundtrack": "саундтрек",
    "singer-songwriter": "авторская песня", "chanson": "шансон", "russian rock": "русский рок",
    "russian pop": "русская поп-музыка", "grunge": "гранж", "emo": "эмо", "shoegaze": "шугейз",
    "post-rock": "пост-рок", "trip-hop": "трип-хоп", "drum and bass": "драм-н-бейс", "dubstep": "дабстеп",
    "lo-fi": "лоу-фай", "doom metal": "дум-метал", "black metal": "блэк-метал", "death metal": "дэт-метал",
    "thrash metal": "трэш-метал", "nu metal": "ню-метал", "metalcore": "металкор", "hardcore": "хардкор",
    "gothic rock": "готик-рок", "industrial": "индастриал", "christian rock": "христианский рок",
    "britpop": "брит-поп", "garage rock": "гаражный рок", "j-pop": "джей-поп", "k-pop": "кей-поп",
}  # fmt: skip


# region Текст


def clean_text(raw: str | None) -> str:
    """Без HTML, ссылки «Read more on Last.fm», скобок (даты, транскрипции, «англ. …») и лишних пробелов."""
    if not raw:
        return ""
    text = _TAG.sub("", _LINK.sub("", raw))
    text = " ".join(html.unescape(text).split())
    previous = None
    while previous != text:  # вложенные скобки — снаружи внутрь
        previous, text = text, _PARENS.sub("", text)
    return _SPACE_BEFORE_PUNCT.sub(r"\1", text).strip()


def is_russian(text: str) -> bool:
    cyr = len(_CYR.findall(text))
    return cyr >= 10 and cyr >= 2 * len(_LAT.findall(text))


def split_sentences(text: str) -> list[str]:
    """Предложения с учётом сокращений («1981 г.», «т. е.», инициалы «В. Цой»)."""
    pieces = _SENTENCE_END.split(text)
    result: list[str] = []
    for piece in pieces:
        if result:
            last_word = re.split(r"[\s(«\"]", result[-1].rstrip("."))[-1]
            if result[-1].endswith(".") and (
                last_word.lower() in _ABBREVIATIONS or (len(last_word) == 1 and last_word.isupper())
            ):
                result[-1] = f"{result[-1]} {piece}"
                continue
        result.append(piece)
    return [s.strip() for s in result if s.strip()]


def _complete(sentence: str) -> bool:
    return (
        sentence[-1:] in ".!?"
        and not sentence.endswith("...")
        and next((ch for ch in sentence if ch.isalnum()), "").isupper()
        and not _WEAK_START.match(sentence)
    )


def _shorten(sentence: str, max_chars: int) -> str | None:
    """Длинное предложение — до законченной фразы по запятой: «X — певец, один из…» → «X — певец.»."""
    body = sentence.rstrip(".!?")
    parts = body.split(", ")
    for count in range(len(parts) - 1, 0, -1):
        tail = parts[count]
        if not _DROPPABLE_TAIL.match(tail):
            continue
        candidate = ", ".join(parts[:count]).rstrip(" ,;:—-") + "."
        if MIN_CHARS <= len(candidate) <= max_chars:
            return candidate
    return None


def pick_sentences(raw: str | None, max_chars: int, limit: int = 2) -> list[str]:
    """Законченные русские предложения из начала описания, каждое целиком ≤ `max_chars`."""
    text = clean_text(raw)
    if not text or not is_russian(text):
        return []
    if text[-1] not in ".!?…":
        text += "."  # описание в Яндексе бывает одной фразой без точки
    result: list[str] = []
    for sentence in split_sentences(text)[:5]:
        if not _complete(sentence) or not is_russian(sentence):
            continue
        candidate = sentence if len(sentence) <= max_chars else _shorten(sentence, max_chars)
        if candidate and len(candidate) >= MIN_CHARS and candidate not in result:
            result.append(candidate)
        if len(result) >= limit:
            break
    return result


def first_sentence(raw: str | None) -> str | None:
    """Первое законченное предложение, которое помещается в лимит (для совместимости)."""
    found = pick_sentences(raw, DEFAULT_MAX_CHARS, 1)
    return found[0] if found else None


def _tags(raw: Any) -> list[str]:
    items = (raw or {}).get("tag") if isinstance(raw, dict) else raw
    if isinstance(items, dict):
        items = [items]
    result: list[str] = []
    for item in items or []:
        name = str((item.get("name") if isinstance(item, dict) else item) or "").strip()
        if name and name.lower() not in _NOISE_TAGS and name.lower() not in (t.lower() for t in result):
            result.append(name)
    return result[:MAX_TAGS]


def _count(n: int) -> str:
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}".rstrip("0").rstrip(".").replace(".", ",") + " млн"
    if n >= 1000:
        return f"{n // 1000} тыс."
    return str(n)


def template_variants(
    artist: str, album: str | None, year: int | None, tags: list[str], listeners: int | None, max_chars: int
) -> list[str]:
    """Шаблонные фразы по фактам метаданных и Last.fm (как «Ведущий сингл альбома «Meliora» (2015)»)."""
    result: list[str] = []
    if album and normalize(album) not in LOOSE_ALBUMS:
        result.append(f"Трек из альбома «{album}» ({year})" if year else f"Трек из альбома «{album}»")
    genres = [GENRES_RU[t.lower()] for t in tags if t.lower() in GENRES_RU][:2]
    if genres:
        result.append("Жанр — " + " и ".join(genres))
    if listeners and listeners >= 1000:
        result.append(f"У {artist} больше {_count(listeners)} слушателей на Last.fm")
    return [r for r in result if len(r) <= max_chars]


# endregion


class ArtistFacts:
    def __init__(
        self,
        lastfm: LastFmClient | None,
        *,
        http: httpx.AsyncClient | None = None,
        yandex: Callable[[], Any] | None = None,
        store: Store | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._lastfm = lastfm
        self._http = http
        self._yandex = yandex
        self._store = store
        self._clock = clock
        self._cache: dict[str, tuple[float, dict[str, Any]]] = {}
        self._wiki_down_until = 0.0
        if store is not None:
            store.executescript(
                "CREATE TABLE IF NOT EXISTS track_blurbs (key TEXT PRIMARY KEY, value TEXT NOT NULL, "
                "created_at INTEGER NOT NULL)"
            )

    @property
    def available(self) -> bool:
        return self._lastfm is not None or self._http is not None or self._yandex is not None

    async def facts(
        self,
        artist: str,
        title: str | None = None,
        album: str | None = None,
        year: int | None = None,
        max_chars: int = DEFAULT_MAX_CHARS,
    ) -> dict[str, Any]:
        """`{"fact": str | None, "variants": [...], "tags": [...], "source": str | None}`."""
        empty: dict[str, Any] = {"fact": None, "variants": [], "tags": [], "source": None}
        artist = artist.strip()
        if not artist or not self.available:
            return empty
        raw_key = "|".join([normalize(artist), normalize(title), normalize(album), str(year or ""), str(max_chars)])
        key = hashlib.sha1(raw_key.encode()).hexdigest()  # noqa: S324 — ключ кэша, не криптография
        cached = self._cache.get(key)
        if cached and self._clock() - cached[0] < (CACHE_TTL_S if cached[1]["fact"] else EMPTY_TTL_S):
            return cached[1]
        stored = self._load(key)
        if stored is not None:
            self._remember(key, stored)
            return stored
        try:
            result = await self._collect(artist, (title or "").strip() or None, album, year, max_chars)
        except LastFmError as e:
            log.info("Текст для «%s»: Last.fm недоступен (%s)", artist, e)
            return empty  # сбой не кэшируется — попробуем при следующем заходе
        self._remember(key, result)
        if result["fact"]:
            self._save(key, result)
        return result

    def _remember(self, key: str, value: dict[str, Any]) -> None:
        if len(self._cache) >= CACHE_MAX:
            self._cache.pop(next(iter(self._cache)))
        self._cache[key] = (self._clock(), value)

    def _load(self, key: str) -> dict[str, Any] | None:
        if self._store is None:
            return None
        row = self._store.query_one("SELECT value, created_at FROM track_blurbs WHERE key = ?", (key,))
        if row is None or time.time() * 1000 - row["created_at"] > STORE_TTL_MS:
            return None
        return json.loads(row["value"])

    def _save(self, key: str, value: dict[str, Any]) -> None:
        if self._store is None:
            return
        self._store.execute(
            "INSERT INTO track_blurbs (key, value, created_at) VALUES (?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value, created_at = excluded.created_at",
            (key, json.dumps(value, ensure_ascii=False), int(time.time() * 1000)),
        )

    async def _collect(
        self, artist: str, title: str | None, album: str | None, year: int | None, max_chars: int
    ) -> dict[str, Any]:
        variants: list[tuple[str, str]] = []

        def add(source: str, texts: list[str]) -> None:
            for text in texts:
                if text not in (v for _, v in variants):
                    variants.append((source, text))

        if title:
            add("wikipedia", pick_sentences(await self._wiki_song(artist, title), max_chars, 1))
        add("yandex", pick_sentences(await self._yandex_description(artist), max_chars))
        add("wikipedia", pick_sentences(await self._wiki_artist(artist), max_chars))

        tags: list[str] = []
        listeners: int | None = None
        lastfm_album: str | None = None
        if self._lastfm is not None:
            if title:
                body = await self._lastfm.call("track.getInfo", artist=artist, track=title, autocorrect=1)
                track = (body or {}).get("track") or {}
                tags = _tags(track.get("toptags"))
                lastfm_album = ((track.get("album") or {}).get("title") or "").strip() or None
            body = await self._lastfm.call("artist.getInfo", artist=artist, autocorrect=1, lang="ru")
            info = (body or {}).get("artist") or {}
            add("lastfm", pick_sentences((info.get("bio") or {}).get("summary"), max_chars, 1))
            tags = tags or _tags(info.get("tags"))
            try:
                listeners = int(((info.get("stats") or {}).get("listeners")) or 0) or None
            except (TypeError, ValueError):
                listeners = None
        add("template", template_variants(artist, album or lastfm_album, year, tags, listeners, max_chars))
        chosen = variants[:MAX_VARIANTS]
        return {
            "fact": chosen[0][1] if chosen else None,
            "variants": [v for _, v in chosen],
            "tags": tags,
            "source": chosen[0][0] if chosen else None,
        }

    # region Источники

    async def _yandex_description(self, artist: str) -> str | None:
        api = self._yandex() if self._yandex else None
        describe = getattr(api, "artist_description", None)
        if api is None or describe is None:
            return None
        try:
            found = await api.search_artists(artist)
            match = next((a for a in found[:5] if normalize(a[1]) == normalize(artist)), None)
            return await describe(match[0]) if match else None
        except Exception as e:  # noqa: BLE001 — Яндекс недоступен: следующий источник
            log.info("Описание «%s» в Яндексе не получено: %s", artist, e)
            return None

    async def _wiki(self, **params: str) -> dict[str, Any] | None:
        if self._http is None or self._clock() < self._wiki_down_until:
            return None
        try:
            response = await self._http.get(WIKI_API, params={"format": "json", **params}, timeout=WIKI_TIMEOUT_S)
            response.raise_for_status()
            return response.json()
        except (httpx.HTTPError, ValueError) as e:
            log.info("ru.wikipedia недоступна, пауза %d мин: %s", WIKI_PAUSE_S // 60, e)
            self._wiki_down_until = self._clock() + WIKI_PAUSE_S
            return None

    async def _wiki_intro(self, query: str) -> list[tuple[str, str]]:
        """(заголовок, вступление) первых статей поиска, без страниц значений."""
        found = await self._wiki(action="query", list="search", srsearch=query, srlimit="3", srprop="")
        titles = [r["title"] for r in ((found or {}).get("query") or {}).get("search") or []]
        if not titles:
            return []
        pages = await self._wiki(
            action="query",
            prop="extracts|pageprops",
            exintro="1",
            explaintext="1",
            redirects="1",
            titles="|".join(titles),
        )
        by_title = {
            p.get("title"): p
            for p in (((pages or {}).get("query") or {}).get("pages") or {}).values()
            if "disambiguation" not in (p.get("pageprops") or {})
        }
        return [(t, by_title[t].get("extract") or "") for t in titles if t in by_title]

    async def _wiki_artist(self, artist: str) -> str | None:
        name = normalize(artist)
        for title, intro in await self._wiki_intro(artist):
            head = intro[:400]
            if (name in normalize(head) or name in normalize(title)) and _MUSIC_WORDS.search(head):
                return intro
        return None

    async def _wiki_song(self, artist: str, title: str) -> str | None:
        name, song = normalize(artist), normalize(title)
        for page, intro in await self._wiki_intro(f"{title} {artist} песня"):
            head = normalize(intro[:500])
            if song in normalize(page) + " " + head and name in head and _SONG_WORDS.search(intro[:300]):
                return intro
        return None

    # endregion
