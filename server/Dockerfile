FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUTF8=1 \
    LANG=C.UTF-8 \
    PYTHONUNBUFFERED=1 \
    DATA_DIR=/data \
    PORT=8790

WORKDIR /app
# ffmpeg — yt-dlp перекладывает поток YouTube (opus/aac) в .opus/.m4a без перекодирования, чтобы записать теги.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY bodrod ./bodrod

RUN useradd --system --uid 1000 bodrod && mkdir -p /data && chown bodrod /data
USER bodrod
VOLUME ["/data"]
EXPOSE 8790

HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request,os;urllib.request.urlopen(f'http://127.0.0.1:{os.environ[\"PORT\"]}/api/health')"
CMD ["python", "-m", "bodrod.main"]
