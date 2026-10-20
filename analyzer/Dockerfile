# Воркер анализа звучания для bodrod: Essentia + TensorFlow, модели discogs-effnet (CC BY-NC-SA 4.0).
# Только linux/amd64: колёс essentia-tensorflow для arm64 нет.
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    LANG=C.UTF-8 \
    TF_CPP_MIN_LOG_LEVEL=2 \
    MODELS_DIR=/models \
    MUSIC_DIR=/music

# ffmpeg декодирует любые форматы (opus, m4a, …) — загрузчики Essentia не используются.
RUN apt-get update && apt-get install -y --no-install-recommends curl ffmpeg && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Модели: эмбеддинг и классификаторы поверх него (граф .pb + метаданные .json с метками и именами тензоров).
ARG MODELS_URL=https://essentia.upf.edu/models
RUN mkdir -p /models && cd /models \
    && curl -fsSLO ${MODELS_URL}/feature-extractors/discogs-effnet/discogs-effnet-bs64-1.pb \
    && curl -fsSLO ${MODELS_URL}/feature-extractors/discogs-effnet/discogs-effnet-bs64-1.json \
    && for head in genre_discogs400/genre_discogs400-discogs-effnet-1 \
                   mood_happy/mood_happy-discogs-effnet-1 \
                   mood_sad/mood_sad-discogs-effnet-1 \
                   mood_aggressive/mood_aggressive-discogs-effnet-1 \
                   mood_relaxed/mood_relaxed-discogs-effnet-1 \
                   danceability/danceability-discogs-effnet-1 \
                   voice_instrumental/voice_instrumental-discogs-effnet-1; do \
         curl -fsSLO ${MODELS_URL}/classification-heads/${head}.pb \
         && curl -fsSLO ${MODELS_URL}/classification-heads/${head}.json; \
       done

COPY bodro_analyzer ./bodro_analyzer

RUN useradd --system --uid 1000 analyzer
USER analyzer
# Низкий приоритет процессора: анализ идёт в фоне и не мешает Navidrome.
CMD ["nice", "-n", "19", "python", "-m", "bodro_analyzer"]
