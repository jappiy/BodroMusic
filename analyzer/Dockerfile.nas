# Сборка анализатора на NAS без интернета: база — образ с зависимостями,
# поверх кладётся код из этой папки. Если изменился requirements.txt — сборка остановится: нужна новая база.
# Сравнение без учёта \r: на ПК файлы с CRLF, в образе — LF. Подробно — docs/DEPLOY.md.
FROM ghcr.io/jappiy/bodromusic-analyzer:0016967
USER root
COPY requirements.txt /tmp/requirements.txt
RUN tr -d '\r' < /tmp/requirements.txt > /tmp/req.new && tr -d '\r' < /app/requirements.txt > /tmp/req.old \
    && { cmp -s /tmp/req.new /tmp/req.old || { echo "requirements.txt изменился: нужна новая база"; exit 1; }; } \
    && rm -rf /app/bodro_analyzer /tmp/requirements.txt /tmp/req.new /tmp/req.old
COPY bodro_analyzer /app/bodro_analyzer
USER analyzer
