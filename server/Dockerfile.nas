# Сборка bodrod на NAS без интернета: база — образ с зависимостями,
# поверх кладётся код из этой папки. Если изменился requirements.txt — сборка остановится: нужна новая база.
# Сравнение без учёта \r: на ПК файлы с CRLF, в образе — LF. Подробно — docs/DEPLOY.md.
FROM ghcr.io/jappiy/bodromusic-server:9f5de2f
USER root
COPY requirements.txt /tmp/requirements.txt
RUN tr -d '\r' < /tmp/requirements.txt > /tmp/req.new && tr -d '\r' < /app/requirements.txt > /tmp/req.old \
    && { cmp -s /tmp/req.new /tmp/req.old || { echo "requirements.txt изменился: нужна новая база"; exit 1; }; } \
    && rm -rf /app/bodrod /tmp/requirements.txt /tmp/req.new /tmp/req.old
COPY bodrod /app/bodrod
USER bodrod
