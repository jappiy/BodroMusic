# bodro-analyzer — анализ звучания для bodrod

Отдельный контейнер: берёт у bodrod задания (пути файлов), считает признаки на Essentia и отдаёт их обратно. bodrod
использует их для фильтра пула «На пробу», настроения «Моей волны» и «Похожих по звучанию».

Что считается: эмбеддинг discogs-effnet (1280 чисел), жанры discogs400 (топ-5), настроения happy / sad / aggressive /
relaxed, danceability, голос/инструментал, BPM, тональность, громкость (EBU R128). Модели Essentia — под
некоммерческой лицензией CC BY-NC-SA 4.0 (для домашнего сервера подходит), скачиваются при сборке образа.

Очередь: сначала новые треки пула и резерва, потом вся библиотека в фоне. Один файл за раз, процесс с `nice 19`.
Аудио декодирует ffmpeg (любой формат: flac, mp3, opus, m4a, …); анализируются 120 секунд трека (у треков длиннее
150 с — с 30-й секунды).

## Запуск

Образ: `ghcr.io/jappiy/bodromusic-analyzer:<хеш коммита>` (только amd64; текущий — `0016967`, 0.2.0). Есть и
`latest`, но в проекте тег закрепляется.

Анализ идёт в дочернем процессе (модели грузятся в нём один раз). Если трек вызвал ошибку, нативный краш или
завис дольше `TRACK_TIMEOUT_S`, процесс убивается, трек уходит в bodrod со статусом `failed`, следующий трек
получает новый процесс. bodrod повторяет упавший трек через час, после трёх неудач подряд больше его не выдаёт.

| Переменная | Описание |
|---|---|
| `BODROD_URL` | Адрес bodrod, например `http://bodrod:8790` |
| `ANALYZER_TOKEN` / `ANALYZER_TOKEN_FILE` | Тот же токен, что `ANALYZER_TOKEN` у bodrod (любая длинная случайная строка) |
| `MUSIC_DIR` | Папка музыки в контейнере; пути заданий — относительно неё (как `MUSIC_DIR` у bodrod) |
| `BATCH`, `IDLE_S` | Сколько заданий брать за раз (10) и пауза, когда заданий нет (300 с) |
| `TRACK_TIMEOUT_S` | Предел анализа одного трека (600 с), после него дочерний процесс убивается |

### На DSM рядом с bodrod (низкий приоритет)

```yaml
  analyzer:
    image: ghcr.io/jappiy/bodromusic-analyzer:0016967
    container_name: bodro-analyzer
    restart: unless-stopped
    cpus: 1.5          # не больше полутора ядер
    mem_limit: 3g
    environment:
      BODROD_URL: http://bodrod:8790
      ANALYZER_TOKEN_FILE: /secrets/analyzer_token
      MUSIC_DIR: /music
    volumes:
      - /volume2/music:/music:ro
      - /volume1/docker/secrets:/secrets:ro
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

### На ПК через SMB

Подключить общую папку музыки (например, `\\nas\music` → `M:`), затем:

```bash
docker run -d --name bodro-analyzer -e BODROD_URL=http://nas.local:8790 -e ANALYZER_TOKEN=<токен> -e MUSIC_DIR=/music --log-opt max-size=10m --log-opt max-file=3 -v M:/:/music:ro ghcr.io/jappiy/bodromusic-analyzer:0016967
```

У bodrod при этом задаётся тот же `ANALYZER_TOKEN` (или файл `/secrets/analyzer_token`).

## Разработка

```bash
pip install -r requirements-dev.txt   # essentia-tensorflow ставится только на Linux x86_64
pytest -q                             # тесты цикла воркера — без Essentia
```
