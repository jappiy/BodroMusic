# Дополнительные модели Essentia для сборки на NAS

`Dockerfile.nas` копирует эту папку в `/models` образа. Сюда кладутся модели, которых нет в базовом образе
(`ghcr.io/jappiy/bodromusic-analyzer:0016967`): `mood_acoustic` и `mood_electronic` (анализатор 0.3.0).
Скачать на ПК: `powershell -File analyzer\fetch-models.ps1`. Файлы `.pb` / `.json` в git не хранятся.
