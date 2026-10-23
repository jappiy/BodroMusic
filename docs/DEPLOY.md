# Установка bodrod и анализатора на сервер (NAS)

**Правило с 2026-10-23: образы `bodrod` и `bodro-analyzer` собираются на NAS из локальной папки.
Новые образы из GHCR (`ghcr.io/jappiy/...:<хеш>`) на сервер не ставятся, хеши коммитов в YAML не пишутся.**

## Схема

| Что | Где |
| --- | --- |
| Код на ПК (источник) | `C:\Projects\BodroMusic\server`, `C:\Projects\BodroMusic\analyzer` |
| Код на NAS (из него сборка) | `\\192.168.1.113\docker\bodrod\src\server`, `...\src\analyzer` (= `/volume1/docker/bodrod/src/...`) |
| Как собирается | `server/Dockerfile.nas`, `analyzer/Dockerfile.nas`: база + код из папки, **интернет не нужен** |
| База (зависимости) | `ghcr.io/jappiy/bodromusic-server:9f5de2f`, `ghcr.io/jappiy/bodromusic-analyzer:0016967` — уже лежат на NAS, не удалять |
| Итоговые образы | `bodrod:local`, `bodro-analyzer:local` — тег всегда один |
| Проект Container Manager | `bodrod`, путь `/volume1/docker/bodrod` |

Обычный `Dockerfile` на NAS собрать нельзя: из сети NAS недоступны `deb.debian.org` и PyPI (CDN Fastly). Он остаётся для CI.

В YAML проекта `bodrod` (уже стоит):

```yaml
  bodrod:
    image: bodrod:local
    pull_policy: build
    build:
      context: /volume1/docker/bodrod/src/server
      dockerfile: Dockerfile.nas
  bodro-analyzer:
    image: bodro-analyzer:local
    pull_policy: build
    build:
      context: /volume1/docker/bodrod/src/analyzer
      dockerfile: Dockerfile.nas
```

## Как обновить (только код)

1. Скопировать код на NAS (PowerShell, по одной команде; не кликать мышкой в окно — консоль встанет на паузу):

   ```
   robocopy C:\Projects\BodroMusic\server \\192.168.1.113\docker\bodrod\src\server /MIR /XD tests __pycache__ .pytest_cache .ruff_cache .venv
   robocopy C:\Projects\BodroMusic\analyzer \\192.168.1.113\docker\bodrod\src\analyzer /MIR /XD tests __pycache__ .pytest_cache .ruff_cache .venv
   ```

   Если менялся только `server/`, вторую команду можно пропустить.
2. Container Manager → Проект → `bodrod` → «Действие → Остановить».
3. «Действие → Создать» — пересобирает `:local` из папки (секунды) и поднимает контейнеры. В терминале должно быть `Exit Code: 0`.
4. Container Manager → Образ: если появились строки с тегом `<none>` (прошлая сборка) — выделить и удалить **только их**.
   Кнопку «Удалить неиспользуемые образы» не нажимать: она снимет базовые образы, и сборка перестанет работать.

## Если изменился `requirements.txt` (или системные пакеты в `Dockerfile`)

Сборка остановится с сообщением «requirements.txt изменился: нужна новая база», а проект к этому моменту уже остановлен.
Поэтому при изменении зависимостей сначала готовят базу, потом обновляют:

1. CI соберёт образ как обычно. На NAS один раз скачать новую базу: Container Manager → Реестр → `ghcr.io/jappiy/bodromusic-server`
   (или `-analyzer`) → нужный тег.
2. Поменять строку `FROM` в `server/Dockerfile.nas` (`analyzer/Dockerfile.nas`) на новый тег, скопировать код (шаг 1 выше), «Остановить» → «Создать».
3. Удалить старую базу в «Образ». На NAS всегда одна база на сервис.

## Чего не делать

- **Не перезапускать пакет Container Manager** ради обновления: это обрывает пересоздание контейнеров, они остаются с временными
  именами вида `e261fa7cefc6_bodrod`, DSM пишет «No such container», контейнеры не останавливаются.
- Не писать в YAML `image: ghcr.io/jappiy/...:<хеш>` и не скачивать образ на каждый коммит.
- Не править `docker-compose.yml` на диске: Container Manager хранит YAML у себя. Менять через «Конфигурации YAML»
  (при остановленном проекте) или API `SYNO.Docker.Project` (`method=update`).

## Документация

В файле задания и в `docs/INFRA.md` вместо тега образа GHCR записывать версию bodrod / анализатора, дату установки
и тег базы, если она менялась.
