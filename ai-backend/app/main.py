from __future__ import annotations

import os
import shutil
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Response, UploadFile, status

from .pipeline import CancellationRequested, PipelineError, process_audio

MAX_UPLOAD_BYTES = int(os.getenv("LYR_MAX_UPLOAD_BYTES", str(250 * 1024 * 1024)))
MAX_KNOWN_LYRICS_CHARACTERS = 200_000
JOB_TTL_SECONDS = int(os.getenv("LYR_JOB_TTL_SECONDS", "3600"))
WORK_ROOT = Path(os.getenv("LYR_WORK_DIR", "/tmp/lyr-ai-jobs"))
ALLOWED_MODES = {"transcribe", "align"}
ALLOWED_LANGUAGES = {"auto", "bn"}


@dataclass
class JobRecord:
    job_id: str
    workspace: Path
    audio_path: Path
    mode: str
    language: str
    known_lyrics: str
    status: str = "queued"
    progress: int = 0
    message: str = "Waiting for the CPU worker"
    result_lrc: str | None = None
    error: str | None = None
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    cancel_event: threading.Event = field(default_factory=threading.Event)
    process: subprocess.Popen[bytes] | None = None
    lock: threading.RLock = field(default_factory=threading.RLock)

    def public_response(self) -> dict:
        with self.lock:
            response: dict = {
                "job_id": self.job_id,
                "status": self.status,
                "progress": self.progress,
                "message": self.message,
            }
            if self.status == "completed" and self.result_lrc:
                response["result"] = {"lrc": self.result_lrc}
            if self.status == "failed" and self.error:
                response["error"] = self.error
            return response


app = FastAPI(
    title="Lyr AI Sync",
    version="1.0.0",
    description="CPU-first singing transcription and known-lyrics alignment for the Lyr Android app.",
)
WORK_ROOT.mkdir(parents=True, exist_ok=True)
JOBS: dict[str, JobRecord] = {}
JOBS_LOCK = threading.RLock()
EXECUTOR = ThreadPoolExecutor(
    max_workers=max(1, int(os.getenv("LYR_MAX_WORKERS", "1"))),
    thread_name_prefix="lyr-ai",
)


def _safe_suffix(filename: str | None) -> str:
    suffix = Path(filename or "audio").suffix.lower()
    if not re_match_suffix(suffix):
        return ".audio"
    return suffix


def re_match_suffix(suffix: str) -> bool:
    return suffix in {
        ".mp3",
        ".m4a",
        ".mp4",
        ".aac",
        ".wav",
        ".flac",
        ".ogg",
        ".opus",
        ".webm",
        ".audio",
    }


def _cleanup_expired_jobs() -> None:
    cutoff = time.time() - JOB_TTL_SECONDS
    with JOBS_LOCK:
        expired = [
            job_id
            for job_id, job in JOBS.items()
            if job.updated_at < cutoff and job.status in {"completed", "failed", "canceled"}
        ]
        for job_id in expired:
            job = JOBS.pop(job_id)
            shutil.rmtree(job.workspace, ignore_errors=True)


def _set_job(
    job: JobRecord,
    *,
    job_status: str | None = None,
    progress: int | None = None,
    message: str | None = None,
    result_lrc: str | None = None,
    error: str | None = None,
) -> None:
    with job.lock:
        if job.cancel_event.is_set() and job_status not in {"canceled"}:
            return
        if job_status is not None:
            job.status = job_status
        if progress is not None:
            job.progress = max(0, min(progress, 100))
        if message is not None:
            job.message = message
        if result_lrc is not None:
            job.result_lrc = result_lrc
        if error is not None:
            job.error = error
        job.updated_at = time.time()


def _register_process(job: JobRecord, process: subprocess.Popen[bytes] | None) -> None:
    with job.lock:
        job.process = process
        if process is not None and job.cancel_event.is_set() and process.poll() is None:
            process.terminate()


def _run_job(job: JobRecord) -> None:
    try:
        if job.cancel_event.is_set():
            raise CancellationRequested()
        _set_job(job, job_status="processing", progress=1, message="CPU processing started")
        lrc = process_audio(
            source=job.audio_path,
            workspace=job.workspace,
            mode=job.mode,
            language=job.language,
            known_lyrics=job.known_lyrics,
            progress=lambda value, message: _set_job(job, progress=value, message=message),
            register_process=lambda process: _register_process(job, process),
            is_canceled=job.cancel_event.is_set,
        )
        if job.cancel_event.is_set():
            raise CancellationRequested()
        # Never expose a terminal response while uploaded audio or stems still exist on disk.
        shutil.rmtree(job.workspace, ignore_errors=True)
        _set_job(
            job,
            job_status="completed",
            progress=100,
            message="Draft ready. Review every phrase before saving.",
            result_lrc=lrc,
        )
    except CancellationRequested:
        shutil.rmtree(job.workspace, ignore_errors=True)
        _set_job(job, job_status="canceled", message="Canceled; uploaded audio deleted")
    except PipelineError as error:
        shutil.rmtree(job.workspace, ignore_errors=True)
        _set_job(
            job,
            job_status="failed",
            message="Processing failed; uploaded audio deleted",
            error=str(error),
        )
    except Exception as error:
        shutil.rmtree(job.workspace, ignore_errors=True)
        _set_job(
            job,
            job_status="failed",
            message="Processing failed; uploaded audio deleted",
            error=f"Unexpected processing error: {error}",
        )
    finally:
        _register_process(job, None)
        # Idempotent safety net for every exit path, including cancellation races.
        shutil.rmtree(job.workspace, ignore_errors=True)


@app.get("/")
def service_info() -> dict:
    return {
        "service": "Lyr AI Sync",
        "status": "ready",
        "processing": "CPU",
        "audio_retention": "deleted after completion, failure, or cancellation",
    }


@app.get("/healthz")
def health() -> dict:
    return {"status": "ok"}


@app.post("/v1/jobs", status_code=status.HTTP_202_ACCEPTED)
async def create_job(
    audio: UploadFile = File(...),
    mode: str = Form(...),
    language: str = Form("auto"),
    lyrics: str = Form(""),
) -> dict:
    _cleanup_expired_jobs()
    mode = mode.strip().lower()
    language = language.strip().lower()
    lyrics = lyrics.strip()
    if mode not in ALLOWED_MODES:
        raise HTTPException(status_code=400, detail="mode must be 'transcribe' or 'align'")
    if language not in ALLOWED_LANGUAGES:
        raise HTTPException(status_code=400, detail="language must be 'auto' or 'bn'")
    if mode == "align" and not lyrics:
        raise HTTPException(status_code=400, detail="lyrics are required in align mode")
    if len(lyrics) > MAX_KNOWN_LYRICS_CHARACTERS:
        raise HTTPException(status_code=413, detail="pasted lyrics are too large")

    job_id = uuid.uuid4().hex
    workspace = WORK_ROOT / job_id
    workspace.mkdir(parents=True, exist_ok=False)
    audio_path = workspace / f"upload{_safe_suffix(audio.filename)}"
    total = 0
    try:
        with audio_path.open("wb") as output:
            while True:
                chunk = await audio.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="audio exceeds the 250 MB limit")
                output.write(chunk)
        if total == 0:
            raise HTTPException(status_code=400, detail="the uploaded audio file is empty")
    except Exception:
        shutil.rmtree(workspace, ignore_errors=True)
        raise
    finally:
        await audio.close()

    job = JobRecord(
        job_id=job_id,
        workspace=workspace,
        audio_path=audio_path,
        mode=mode,
        language=language,
        known_lyrics=lyrics,
    )
    with JOBS_LOCK:
        JOBS[job_id] = job
    try:
        EXECUTOR.submit(_run_job, job)
    except Exception:
        with JOBS_LOCK:
            JOBS.pop(job_id, None)
        shutil.rmtree(workspace, ignore_errors=True)
        raise HTTPException(status_code=503, detail="the CPU worker is unavailable")
    return job.public_response()


@app.get("/v1/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    _cleanup_expired_jobs()
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="job not found or expired")
    return job.public_response()


@app.delete("/v1/jobs/{job_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_job(job_id: str) -> Response:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if job is None:
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    job.cancel_event.set()
    process: subprocess.Popen[bytes] | None
    with job.lock:
        process = job.process
    if process is not None and process.poll() is None:
        try:
            process.terminate()
            process.wait(timeout=5)
        except Exception:
            try:
                process.kill()
            except Exception:
                pass
    shutil.rmtree(job.workspace, ignore_errors=True)
    with job.lock:
        job.status = "canceled"
        job.message = "Canceled; uploaded audio deleted"
        job.result_lrc = None
        job.updated_at = time.time()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
