# Lyr AI Sync backend

This is the cloud worker used by **Lyrics Center → AI Sync**. It runs entirely on CPU and does not call a paid transcription API.

## What it does

1. accepts one local audio upload (`MP3`, `M4A`, `WAV`, `FLAC`, and other FFmpeg-readable audio);
2. decodes it with FFmpeg;
3. isolates the vocal stem with Demucs when the host has enough resources;
4. transcribes Bengali or automatically detected singing with `faster-whisper` and word timestamps;
5. either builds lyric phrases from the transcription or aligns pasted lyrics to those timestamps;
6. emits an end-aware LRC draft—each text cue is followed by an empty timestamp that makes Lyr blank the lyric during a vocal gap; and
7. deletes the uploaded audio, decoded audio, and vocal stems in a `finally` block after success, failure, or cancellation.

Only the text result remains in memory for one hour by default. Restarting the container clears all jobs and results. The Android app does not contain a backend secret and never publishes a result to LRCLIB automatically.

## Run with Docker

The initial image build and first Demucs model download are large. A song may take many minutes on a free 2-vCPU host.

```bash
docker build -t lyr-ai-sync .
docker run --rm -p 7860:7860 \
  -e LYR_CPU_THREADS=2 \
  -e LYR_MAX_WORKERS=1 \
  lyr-ai-sync
```

Check `http://localhost:7860/healthz`. For the Android app, deploy behind HTTPS and enter only the base address, for example `https://your-name-lyr-ai.hf.space`.

### Hugging Face Docker Space (free CPU)

1. Sign in at `huggingface.co`, choose **New Space**, and select **Docker** as the SDK.
2. Choose the free CPU hardware. A public Space also makes the unauthenticated endpoint public, so monitor usage and follow the host's terms.
3. Copy the contents of this `ai-backend` directory to the Space repository so `Dockerfile` is at its root.
4. Wait for the image to build and for the Space to show **Running**.
5. Open `https://YOUR-SPACE.hf.space/healthz`. It must return `{"status":"ok"}`.
6. In Lyr, open **Lyrics Center → AI Sync**, enter `https://YOUR-SPACE.hf.space`, select a mode, and confirm the upload disclosure.

Free Spaces can sleep. A cold model download/start can therefore be slow. The app keeps polling while the job is queued or processing.

## API used by Android

- `POST /v1/jobs` — multipart fields `audio`, `mode=transcribe|align`, `language=auto|bn`, and optional `lyrics`.
- `GET /v1/jobs/{job_id}` — returns queued/processing/completed/failed status and the end-aware LRC result.
- `DELETE /v1/jobs/{job_id}` — requests cancellation, terminates an active FFmpeg/Demucs process, and removes job files.

Uploads are limited to 250 MB. One worker is the safe default for a 2-vCPU/16-GB free host.

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `LYR_WHISPER_MODEL` | `large-v3-turbo` | Multilingual faster-whisper model. |
| `LYR_WHISPER_COMPUTE_TYPE` | `int8` | CPU inference type. |
| `LYR_CPU_THREADS` | `2` | CTranslate2 CPU threads. |
| `LYR_MAX_WORKERS` | `1` | Concurrent songs; keep at one on free CPU. |
| `LYR_SEPARATE_VOCALS` | `1` | Set to `0` to skip Demucs and process the original mix. |
| `LYR_DEMUCS_MODEL` | `htdemucs` | Demucs separation model. |
| `LYR_JOB_TTL_SECONDS` | `3600` | Text-result retention in memory. |
| `LYR_WORK_DIR` | `/tmp/lyr-ai-jobs` | Temporary job directory. |

## Tests

The timing and phrase tests do not download an AI model:

```bash
python -m pip install pytest
pytest
```

A passing unit test is not evidence of singing accuracy. Before calling a deployment production-ready, test a legally obtained real recording—especially Bengali singing—and compare every phrase start and exclusive end while listening. AI output is deliberately a draft that must be reviewed in Lyr before private save.
