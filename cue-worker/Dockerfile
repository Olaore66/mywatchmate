# MyWatchMate AI cue worker: watches live rooms' streams and reports giving QR codes to the backend.
# Build from this folder:  docker build -t mywatchmate-cue-worker .
FROM python:3.13-slim

# ffmpeg reads the streams; nothing else is needed from the system
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY worker ./worker

# Never run as root
RUN useradd --system --no-create-home worker
USER worker

ENV PYTHONUNBUFFERED=1 HEALTH_PORT=8090
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=4)" || exit 1

CMD ["python", "-m", "worker.main"]
