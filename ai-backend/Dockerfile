FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=7860 \
    LYR_CPU_THREADS=2 \
    LYR_MAX_WORKERS=1 \
    LYR_SEPARATE_VOCALS=1 \
    LYR_WHISPER_MODEL=large-v3-turbo

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /service
COPY requirements.txt ./
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.4.1 torchaudio==2.4.1 \
    && pip install -r requirements.txt

COPY app ./app
RUN useradd --create-home --uid 1000 lyr \
    && mkdir -p /tmp/lyr-ai-jobs \
    && chown -R lyr:lyr /service /tmp/lyr-ai-jobs
USER lyr

EXPOSE 7860
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
