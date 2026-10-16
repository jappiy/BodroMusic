# BodroMusic — срез серверной части (инфраструктура)

Состояние на 2026-10-16, снято с живого сервера через DSM API.
Документ для Claude Code: как устроен сервер, какие контейнеры работают, как идут прослушивание,
распознавание и скачивание, что осталось. История работ — в `docs/history/`.
Про само Android-приложение подробно написано в `docs/HANDOFF.md`, здесь только его связь с сервером.
Предлагаемое место в репозитории — `docs/INFRA.md`.

Секретов в документе нет. Пароли и ключи лежат только на сервере (см. раздел 7).

---

## 1. Сервер

- **Железо и ОС:** Xpenology с DSM 7 (Container Manager), имя `NASA-Bodrov`, LAN-адрес `192.168.1.113`.
  Веб-интерфейс DSM: `https://192.168.1.113:5001/`.
- **Сеть:** роутер Keenetic, белый IP и DDNS. Порты наружу для музыкального стека сейчас не пробрасывались,
  кроме желательного `50300/tcp` для Soulseek.
- **Пользователь-владелец музыки:** UID `1027`, GID `100` (`users`). Под ним работают почти все контейнеры
  (`PUID=1027 PGID=100` или `user: "1027:100"`).
- **Тома:**
  - `/volume1/docker/<проект>/` — конфиги и compose-проекты (общая папка DSM `docker`);
  - `/volume2/music/` — вся музыка (общая папка DSM `music`).

### Структура `/volume2/music`

| Папка | Кто пишет | Видит ли Navidrome | Назначение |
| --- | --- | --- | --- |
| `library/` | Lidarr, bodrod | да | Основная библиотека: альбомы Lidarr (`Исполнитель/Альбом/`), с 0.2.0 сюда же треки bodrod |
| `collections/` | beets, slsk-worker, slsk-sweeper | да | Сборники с RuTracker и отдельные треки (`Треки/<исполнитель>/`), разложены beets по исполнителям |
| `explo/me/`, `explo/wife/` | Explo | да | Еженедельные подборки рекомендаций ListenBrainz (свои и жены) |
| `slsk/` | slskd | **нет** (`.ndignore`) | Загрузки Soulseek, общие для Soularr, slsk-worker и bodrod; `slsk/.incomplete` — недокачанное |
| `downloads/` | qBittorrent | **нет** (`.ndignore`) | Торрент-загрузки, отсюда Lidarr импортирует хардлинками |
| `staging/` | beets | — | Промежуточная папка для импорта beets |
| `music-import/` | — | — | Старая папка импорта, сейчас не используется ни одним контейнером |

Navidrome сканирует весь `/volume2/music` (`/music`, только чтение). Папки скрываются от него файлом `.ndignore`.

---

## 2. Контейнеры и compose-проекты

Все проекты созданы в Container Manager: **Проект → путь `/volume1/docker/<имя>`**.
Container Manager хранит YAML проекта у себя: правка `docker-compose.yml` на диске **не применяется**.
Менять нужно через интерфейс (вкладка «Конфигурации YAML», только при остановленном проекте)
или через API `SYNO.Docker.Project` (`method=update`, `id`, `content`), затем «Действие → Создать» (build).

### Проект `bodromusic` — ядро (сеть `bodromusic_default`)

| Контейнер | Образ | Порт | Тома | Роль |
| --- | --- | --- | --- | --- |
| `navidrome` | `deluan/navidrome:latest` | 4533 | `docker/bodromusic/navidrome:/data`, `music:/music:ro` | Музыкальный сервер, Subsonic/OpenSubsonic API |
| `lidarr` | `lscr.io/linuxserver/lidarr:latest` | 8686 | `docker/bodromusic/lidarr:/config`, `music:/volume2/music` | Альбомы и дискографии по MusicBrainz |
| `prowlarr` | `lscr.io/linuxserver/prowlarr:latest` | 9696 | `docker/bodromusic/prowlarr:/config` | Индексаторы для Lidarr |
| `explo` | `ghcr.io/lumepart/explo:latest` | 7288 | `.env`, `config`, `music/explo/me:/data` | Подборки ListenBrainz для пользователя `jappi` |
| `explo-wife` | `ghcr.io/lumepart/explo:latest` | 7289 | `.env`, `config`, `music/explo/wife:/data` | То же для пользователя жены (`kalainidi`) |
| `liked2lidarr` | `python:3.12-alpine` | — | `docker/bodromusic/liked2lidarr:/app`, `…/data:/data` | Лайки в Navidrome → артист в Lidarr |

Настройки Navidrome (через переменные окружения):
- `ND_AGENTS=audiomuseai,deezer,listenbrainz`, `ND_LASTFM_ENABLED=false`, `ND_LISTENBRAINZ_ENABLED=true`;
- `ND_SCANSCHEDULE=@every 1h`, `ND_PLUGINS_ENABLED=true`, `ND_ENABLESHARING=true`, `ND_DEFAULTLANGUAGE=ru`;
- `ND_IMAGECACHESIZE=500MB`, `ND_TRANSCODINGCACHESIZE=2GB`.

Last.fm отключён сознательно: настроить его не удалось. Метаданные дают Deezer и ListenBrainz.

`liked2lidarr`: `NAVIDROME_USER_1=jappi`, `NAVIDROME_USER_2=kalainidi`, `STARRED_TYPES=artist,album,song`,
`MONITOR=latest`, `MONITOR_NEW_ITEMS=all`, `ROOT_FOLDER=/volume2/music/library`, `INTERVAL_MINUTES=15`.
**`DRY_RUN=true`: по-прежнему работает вхолостую**, только пишет в журнал.
Обращается к Navidrome и Lidarr по IP хоста (`192.168.1.113:4533`, `:8686`).

### Отдельные контейнеры (сеть `bridge`)

| Контейнер | Образ | Порт | Роль |
| --- | --- | --- | --- |
| `qbittorrent` | `linuxserver/qbittorrent:latest` | 8080, 6881 | Торренты; монтирует `music:/volume2/music` по тому же пути, что и Lidarr, поэтому импорт идёт хардлинками и раздача продолжается |
| `flaresolverr` | `flaresolverr/flaresolverr:latest` | 8191 | Обход Cloudflare для индексаторов Prowlarr |

### Проект `slskd` (сеть `slskd_default`)

| Контейнер | Образ | Порт | Тома |
| --- | --- | --- | --- |
| `slskd` | `slskd/slskd:latest` (0.26.1) | 5030 (веб/API), 50300 (Soulseek) | `docker/slskd:/app`, `music/slsk:/downloads`, раздаёт `library` и `collections` (ro) |

`slskd.yml`: `directories.downloads: /downloads`, `incomplete: /downloads/.incomplete`. Один API-ключ `worker`
(`role: readwrite`), его используют Soularr, slsk-worker и bodrod. **В строке ключа есть русский комментарий**
(`key: …  # сгенерируется автоматически`), поэтому при извлечении ключа комментарий нужно отрезать.
slskd сохраняет готовый файл в `/downloads/<имя родительской папки у пира>/<файл>`.

### Проект `soularr` (сеть `soularr_default`)

| Контейнер | Образ | Роль |
| --- | --- | --- |
| `soularr-config` | `alpine:3.20` | Одноразовый: берёт ключи из `lidarr/config.xml` и `slskd.yml`, пишет конфиг Soularr в `docker/soularr` |
| `soularr` | `mrusse08/soularr:latest` | Каждые 300 с берёт «wanted» из Lidarr, ищет альбомы в slskd, после загрузки отдаёт в Lidarr на импорт |

В DSM статус проекта оранжевый («требует внимания»). Это нормально: `soularr-config` штатно завершается.

### Проект `beets` (сеть `beets_default`, к slskd ходит по IP хоста)

| Контейнер | Образ | Роль |
| --- | --- | --- |
| `beets` | `lscr.io/linuxserver/beets:latest`, порт 8337 (web) | Импорт и тегирование сборников RuTracker в `collections` (`/music`), `staging`, `downloads:ro` |
| `slsk-worker` | тот же образ, скрипт `/config/scripts/slsk_worker.py` | Очередь `/config/slsk/queue.txt` («Исполнитель - Трек»): пропускает то, что уже есть; ищет в slskd (flac или 320, без ремиксов, live, караоке); качает; чинит теги; импортирует beets-singleton в `collections/Треки/<исполнитель>/`. Итоги — в `state.json`, `status.txt`, `notfound.txt` |
| `slsk-sweeper` | тот же образ, скрипт `/config/scripts/slsk_sweep.py` | Каждые 15 мин импортирует через beets в `collections` всё, что лежит в `slsk/` дольше 30 мин и не забрано другими. Альбомы — альбомом, сборники — поштучно; `failed_imports` тоже обрабатывает; что beets не взял — в `slsk/_unsorted`. Аудио никогда не удаляет |

Скрипты лежат в `/volume1/docker/beets/scripts/`.

### Проект `bodrod` — сервис загрузок приложения (сети `bodromusic_default` и `slskd_default`)

| Контейнер | Образ | Роль |
| --- | --- | --- |
| `bodrod-secrets` | `alpine:3.20` | Одноразовый при каждом старте: достаёт `ApiKey` из `lidarr/config.xml` и ключ slskd из `slskd.yml`, пишет `/volume1/docker/bodrod/secrets/env` (`chmod 600`), делает `chown -R 1027:100` для `env` и `data` |
| `bodrod` | `ghcr.io/jappiy/bodromusic-server:e039796` (0.2.1; в репозитории уже 0.4.0 — обновить, см. задания 003 и 005), порт 8790 | FastAPI-сервис: поиск вне библиотеки и заявки на скачивание; с 0.3.0 — «На удаление»; с 0.4.0 — ночное «Обслуживание библиотеки» |

Ключевые настройки `bodrod`:
```yaml
user: "1027:100"
entrypoint: ["sh", "-c", ". /secrets/env && exec python -m bodrod.main"]
volumes:
  - /volume1/docker/bodrod/data:/data          # SQLite с заявками
  - /volume1/docker/bodrod/secrets:/secrets:ro
  - /volume2/music:/music
environment:
  LANG: C.UTF-8
  PYTHONUTF8: "1"
  NAVIDROME_URL: http://navidrome:4533
  NAVIDROME_ADMIN_USER: bodrod                 # отдельный админ Navidrome
  NAVIDROME_ADMIN_PASSWORD: "…"                # только в YAML проекта на сервере
  LIDARR_URL: http://lidarr:8686
  SLSKD_URL: http://slskd:5030
  SLSKD_DOWNLOADS_DIR: /music/slsk
  LIBRARY_DIR: /music/library
  # YANDEX_MUSIC_TOKEN: не задан, поиск Яндекса идёт анонимно
  # с 0.3.0 (задание 003): NAVIDROME_DB, LIDARR_MUSIC_DIR, SLSK_WORKER_QUEUE + том папки Navidrome
  # с 0.4.0 (задание 005): TZ, MAINTENANCE_DRY_RUN (первый прогон), при желании MAINTENANCE_AT, QUARANTINE_DAYS
```
Регламент 0.4.0 пишет в `/volume2/music` (теги, `cover.jpg`, перенос альбомов) и складывает убранное в
`/volume2/music/.bodrod-quarantine` (30 дней, внутри `.ndignore`). Файлы из папок исполнителей Lidarr не трогает.
Пакет `bodromusic-server` на GHCR публичный, теги — короткие хеши коммитов. В проекте тег закреплён, `latest` не используется.

### Не относятся к музыке

На сервере также работают `jellyfin`, `frigate`, `go2rtc`, `finance-server`, `invest-dashboard`; `mattermost` остановлен.

---

## 3. Прослушивание

```
Телефон (BodroMusic / Symfonium) ──Subsonic API──► navidrome:4533 ──► /volume2/music (весь, кроме .ndignore)
```

- **Клиенты:**
  - собственное Android-приложение **BodroMusic** (`ru.bodro.music`, версия 0.7.0 / 0.8.0 в ветке `claude/new-session-fi1p6v`);
  - запасной клиент **Symfonium**.

  Оба работают через Subsonic/OpenSubsonic API Navidrome с token-авторизацией.
- **Учётки Navidrome:** `jappi` (владелец), `kalainidi` (жена), `bodrod` (служебный админ для сервиса загрузок).
  У каждого человека свои лайки, плейлисты и история; библиотека общая.
- **LAN/WAN:** приложение само выбирает адрес по доступности (`ServerSelector` / `PingServerProbe`).
  В LAN это `http://192.168.1.113:4533`.
- **Метаданные:** Deezer (фото, биографии, похожие, топ-треки) и ListenBrainz (похожие артисты, скробблинг).
  Агент `audiomuseai` указан первым в `ND_AGENTS`, но папка плагинов Navidrome пуста и сервера AudioMuse-AI
  среди контейнеров нет, так что этот агент, скорее всего, ничего не делает. **Проверить.**
- **Скробблинг:** на ListenBrainz. Офлайн-очередь скробблов хранится в приложении.
- **Рекомендации:**
  - «Моя волна» и миксы считаются **в приложении** (`WaveEngine`, `LocalWaveSource`) по лайкам, дизлайкам и истории;
  - на сервере есть еженедельные подборки ListenBrainz через Explo, отдельно для каждого пользователя
    (`explo/me`, `explo/wife`). Жена хочет свою отдельную «волну».
- **Сканирование:** Navidrome сканирует раз в час. Lidarr при импорте и bodrod после размещения файла
  дополнительно запускают `startScan`.

---

## 4. Распознавание (как музыка получает правильные теги и как её находят)

Распознавание идёт тремя путями, в зависимости от источника.

1. **Альбомы через Lidarr (торренты и Soularr).** Lidarr сопоставляет релизы с MusicBrainz и переименовывает
   файлы в `library/Исполнитель/Альбом/`. Слабое место: **русскую музыку Lidarr распознаёт плохо**, многих
   исполнителей и релизов нет в MusicBrainz. Поэтому сборники идут мимо Lidarr.
2. **Сборники и одиночные треки через beets.** beets (MusicBrainz-автотеггер) импортирует сборники RuTracker,
   очередь `slsk-worker` и «подметённое» из `slsk/` в `collections/`. Для сборников и VA используется режим singleton.
3. **Заявки из приложения через bodrod:**
   - **Распознавание запроса.** Пользователь ищет в приложении. Сначала идёт `search3` в Navidrome.
     Если ничего нет, вызывается `GET /api/v1/discover`. bodrod ищет в каталоге **Яндекс Музыки**
     (неофициальный API, без токена: поиск и метаданные подписки не требуют) и переходит на **Deezer**,
     если Яндекс не ответил. Получается каноническое «исполнитель — название — альбом — длительность — обложка».
   - **Проверка «уже есть?».** Перед поиском в Soulseek bodrod ищет трек в Navidrome (`find_in_library`, `search3`).
   - **Выбор файла в Soulseek** (`matching.rank_candidates`):
     - разрешённые форматы `flac, mp3, m4a, ogg, opus, aac, wav, alac`, минимальный битрейт 192;
     - сверка с названием и длительностью из каталога;
     - до 5 кандидатов (`SLSKD_MAX_CANDIDATES`); если загрузка встала или не удалась, берётся следующий.
   - **Теги.** С 0.2.0 bodrod сам прописывает `artist`, `albumartist`, `title`, `album` и обложку (mutagen)
     из метаданных каталога. Кривые теги из Soulseek на поиск в библиотеке больше не влияют.

---

## 5. Скачивание

Источников четыре. Все они пишут в один и тот же `/volume2/music`.

### 5.1. Альбомы и дискографии: Lidarr

```
Lidarr (wanted) ─┬─► Prowlarr/FlareSolverr ─► qBittorrent ─► music/downloads ─► (хардлинк) ─► music/library
                 └─► Soularr ─► slskd ─► music/slsk ─► Lidarr import ─► music/library
```
Артисты попадают в Lidarr вручную, через `liked2lidarr` (лайк артиста, альбома или трека; сейчас в режиме DRY_RUN)
и через кнопку «Скачать всё» в приложении (bodrod добавляет артиста с мониторингом).
RuTracker в схеме не участвует: заставить его работать не удалось.

### 5.2. Отдельные треки из очереди-файла: slsk-worker

Строки дописываются в `/volume1/docker/beets/config/slsk/queue.txt`. Сюда же загружен экспорт лайков из Яндекс Музыки.
Результат: `collections/Треки/<исполнитель>/`; что не нашлось — в `notfound.txt`.

### 5.3. Заявки из приложения: bodrod

API (авторизация теми же Subsonic-заголовками `X-Subsonic-User` / `-Token` / `-Salt`, bodrod проверяет их в Navidrome):
`GET /api/health`, `GET /api/v1/info`, `GET /api/v1/discover`, `POST /api/v1/requests`, `GET /api/v1/requests`;
с 0.4.0 — `GET /api/v1/artists/popular`, `POST /api/v1/library/check`, `/api/v1/maintenance*`. Полный список — `server/README.md`.

В приложении адрес сервиса указывается в **Настройки → Загрузки** (`http://192.168.1.113:8790`) отдельно для каждой учётки.
`DownloadWatchWorker` опрашивает заявки и показывает уведомление.

**Трек** — шаги хранятся в SQLite и переживают рестарт:
```
queued ─► (уже в библиотеке? → completed)
       ─► searching    POST /api/v0/searches в slskd, ожидание до 25 с
       ─► downloading  enqueue лучшего кандидата, опрос transfer; срыв или долгая очередь → следующий кандидат
       ─► placing      (0.2.0) найти файл в /music/slsk → копия .part рядом с назначением → теги и обложка
                        → os.replace в /music/library/{artist}/{album}/{artist} - {title}.ext (без альбома → Singles)
                        → удалить исходник и пустую папку пира
       ─► importing    startScan, ждать появления в Navidrome до 15 мин
       ─► completed
```

**Исполнитель:** `queued ─► downloading` (Lidarr добавляет артиста и ищет дискографию) `─► completed`.
Сдаётся через 24 часа. Отмена из приложения (0.4.0) снимает мониторинг исполнителя в Lidarr и убирает его
загрузки из очереди; уже скачанные файлы остаются.

**Как bodrod уживается с остальными:**
- папку `slsk/` делят slskd, Soularr, slsk-worker, slsk-sweeper и bodrod;
- bodrod забирает свой файл сразу после загрузки, а sweeper трогает только то, что лежит дольше 30 минут;
  если `placing` у bodrod не удался, файл через полчаса подберёт sweeper и уложит в `collections`;
- папку пира bodrod удаляет только пустой, чтобы не задеть файлы Soularr.

### 5.4. Ручные торренты

Через qBittorrent в `music/downloads`.

---

## 6. История работ

Что и когда менялось на сервере — в файлах заданий `docs/history/` (индекс — `docs/HANDOFF.md`, раздел 10).
Развёртывание bodrod 0.2.x (сессия 2026-10-16) — [`docs/history/002-bodrod-deploy.md`](history/002-bodrod-deploy.md).
bodrod 0.4.0 (регламент обслуживания библиотеки) — [`docs/history/005-maintenance-artist-playback.md`](history/005-maintenance-artist-playback.md).

---

## 7. Секреты и доступы

- Ключ Lidarr: `/volume1/docker/bodromusic/lidarr/config.xml` (`<ApiKey>`).
- Ключ slskd: `/volume1/docker/slskd/slskd.yml` → `web.authentication.api_keys.worker.key`.
- Для bodrod оба ключа копируются в `/volume1/docker/bodrod/secrets/env` (600, владелец 1027) при каждом старте проекта.
- Пароль служебного админа Navidrome `bodrod` записан **открытым текстом в YAML проекта `bodrod`** в DSM.
  Лучше перенести его в `secrets/` по той же схеме.
- Пароли Navidrome для `liked2lidarr` — в его compose (`NAVIDROME_PASSWORD_1/2`).
- Не выводить содержимое этих файлов в логи и ответы.

---

## 8. Открытые вопросы и следующие шаги

1. **Перенос в `library` на реальном файле ещё не проверен.** Первая живая заявка («ЙОП ШОУ — Сексофонист»)
   дошла до поиска, но получила 0 ответов в Soulseek. Нужен тест на распространённом треке и проверка лога:
   откуда взят файл, куда положен, какие теги записаны, удалён ли исходник, нашёл ли его Navidrome.
2. **Два механизма для одиночных треков:** `slsk-worker` кладёт в `collections/Треки/<исполнитель>/`,
   bodrod — в `library/<исполнитель>/<альбом>/`. Стоит выбрать одно место и одну схему именования.
   Возможно, перевести очередь `queue.txt` на API bodrod.
3. **«Скачать всё» и русская музыка:** Lidarr часто не знает исполнителя. Нужен запасной путь:
   дискография из Яндекса и поиск альбомов целиком в slskd.
4. **`liked2lidarr` в `DRY_RUN=true`:** решить, включать ли.
5. **AudioMuse-AI:** агент прописан в Navidrome, но плагина и сервера нет. Либо установить, либо убрать из `ND_AGENTS`.
6. **Отдельная «волна» для жены:** сейчас разделение есть на уровне учёток Navidrome и двух экземпляров Explo;
   проверить, что «Моя волна» в приложении считается по учётке, а не по общей библиотеке.
7. **Пароль `bodrod` в YAML:** перенести в `secrets/`.
8. **Внешний доступ:** через встроенный reverse proxy DSM с Let's Encrypt на DDNS. Порты 4533 и 8790 на роутере не открывать.
9. **Порт 50300 на Keenetic** для slskd: проверить проброс, без него меньше пиров и хуже поиск.
