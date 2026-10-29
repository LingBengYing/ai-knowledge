FROM python:3.11-slim-bookworm
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && groupadd -g 10001 knowledge && useradd -u 10001 -g 10001 -M knowledge \
    && install -d -o 10001 -g 10001 -m 0700 /run/knowledge-ocr
COPY ocr_service ./ocr_service
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OCR_SOCKET=/run/knowledge-ocr/page.sock OCR_MIN_REQUEST_INTERVAL_MS=6000 OCR_CONCURRENCY=4
CMD ["python", "-m", "ocr_service"]
