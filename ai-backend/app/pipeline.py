from __future__ import annotations

import math
import os
import re
import shutil
import subprocess
import sys
import threading
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from statistics import median
from typing import Callable, Iterable, Sequence

ProgressCallback = Callable[[int, str], None]
ProcessCallback = Callable[[subprocess.Popen[bytes] | None], None]
CancelCallback = Callable[[], bool]


class PipelineError(RuntimeError):
    """A processing failure that is safe to return to the app."""


class CancellationRequested(RuntimeError):
    pass


@dataclass(frozen=True)
class TimedWord:
    text: str
    start: float
    end: float
    segment_break: bool = False


@dataclass(frozen=True)
class TimedCue:
    text: str
    start: float
    end: float


_MODEL = None
_MODEL_NAME = None
_MODEL_LOCK = threading.Lock()


def _check_canceled(is_canceled: CancelCallback) -> None:
    if is_canceled():
        raise CancellationRequested()


def _run_process(
    command: list[str],
    register_process: ProcessCallback,
    is_canceled: CancelCallback,
    failure_message: str,
) -> None:
    _check_canceled(is_canceled)
    process = subprocess.Popen(
        command,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    register_process(process)
    try:
        _, stderr = process.communicate()
    finally:
        register_process(None)
    _check_canceled(is_canceled)
    if process.returncode != 0:
        detail = stderr.decode("utf-8", errors="replace").strip().splitlines()
        suffix = f" ({detail[-1][:240]})" if detail else ""
        raise PipelineError(f"{failure_message}{suffix}")


def _load_model(progress: ProgressCallback, is_canceled: CancelCallback):
    global _MODEL, _MODEL_NAME
    model_name = os.getenv("LYR_WHISPER_MODEL", "large-v3-turbo").strip()
    with _MODEL_LOCK:
        if _MODEL is not None and _MODEL_NAME == model_name:
            return _MODEL
        _check_canceled(is_canceled)
        progress(34, f"Loading the {model_name} transcription model")
        try:
            from faster_whisper import WhisperModel

            _MODEL = WhisperModel(
                model_name,
                device="cpu",
                compute_type=os.getenv("LYR_WHISPER_COMPUTE_TYPE", "int8"),
                cpu_threads=max(1, int(os.getenv("LYR_CPU_THREADS", "2"))),
                num_workers=1,
            )
            _MODEL_NAME = model_name
        except Exception as error:  # Model download/load errors vary by backend.
            raise PipelineError(f"The transcription model could not be loaded: {error}") from error
    return _MODEL


def _prepare_audio(
    source: Path,
    workspace: Path,
    progress: ProgressCallback,
    register_process: ProcessCallback,
    is_canceled: CancelCallback,
) -> Path:
    normalized = workspace / "normalized.wav"
    progress(3, "Decoding the audio")
    _run_process(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-vn",
            "-ac",
            "2",
            "-ar",
            "44100",
            "-c:a",
            "pcm_s16le",
            str(normalized),
        ],
        register_process,
        is_canceled,
        "FFmpeg could not decode this audio file",
    )
    progress(8, "Audio decoded")

    separate_vocals = os.getenv("LYR_SEPARATE_VOCALS", "1").lower() not in {
        "0",
        "false",
        "no",
    }
    if not separate_vocals:
        return normalized

    output_dir = workspace / "separated"
    model_name = os.getenv("LYR_DEMUCS_MODEL", "htdemucs").strip()
    progress(10, "Separating vocals from instruments on CPU")
    try:
        _run_process(
            [
                sys.executable,
                "-m",
                "demucs",
                "--two-stems",
                "vocals",
                "--device",
                "cpu",
                "--shifts",
                "0",
                "--overlap",
                "0.1",
                "-n",
                model_name,
                "-o",
                str(output_dir),
                str(normalized),
            ],
            register_process,
            is_canceled,
            "Vocal separation failed",
        )
        vocals = output_dir / model_name / normalized.stem / "vocals.wav"
        if vocals.is_file() and vocals.stat().st_size > 44:
            progress(30, "Vocals isolated")
            return vocals
    except CancellationRequested:
        raise
    except Exception:
        # Transcription can still produce a result when Demucs cannot run on a constrained host.
        progress(30, "Vocal separation unavailable; using the original mix")
        return normalized

    progress(30, "Vocal separation unavailable; using the original mix")
    return normalized


def _transcribe_words(
    audio: Path,
    language: str,
    progress: ProgressCallback,
    is_canceled: CancelCallback,
) -> list[TimedWord]:
    model = _load_model(progress, is_canceled)
    _check_canceled(is_canceled)
    progress(40, "Listening for sung words")
    requested_language = None if language == "auto" else language
    initial_prompt = None
    if requested_language == "bn":
        initial_prompt = "এটি একটি বাংলা গান। গানের কথাগুলো বাংলা লিপিতে লিখুন।"
    try:
        segments, info = model.transcribe(
            str(audio),
            language=requested_language,
            beam_size=max(1, int(os.getenv("LYR_BEAM_SIZE", "5"))),
            word_timestamps=True,
            vad_filter=False,
            condition_on_previous_text=False,
            temperature=0.0,
            initial_prompt=initial_prompt,
        )
        duration = max(float(getattr(info, "duration", 0.0) or 0.0), 1.0)
        words: list[TimedWord] = []
        for segment in segments:
            _check_canceled(is_canceled)
            segment_words = list(getattr(segment, "words", None) or [])
            usable: list[TimedWord] = []
            for item in segment_words:
                text = str(getattr(item, "word", ""))
                start = float(getattr(item, "start", 0.0) or 0.0)
                end = float(getattr(item, "end", start) or start)
                if text.strip() and math.isfinite(start) and math.isfinite(end) and end > start:
                    usable.append(TimedWord(text=text, start=max(0.0, start), end=end))
            if usable:
                usable[-1] = TimedWord(
                    text=usable[-1].text,
                    start=usable[-1].start,
                    end=usable[-1].end,
                    segment_break=True,
                )
                words.extend(usable)
            segment_end = float(getattr(segment, "end", 0.0) or 0.0)
            progress(
                min(88, 40 + int(48 * max(0.0, min(segment_end / duration, 1.0)))),
                "Transcribing vocals",
            )
    except CancellationRequested:
        raise
    except Exception as error:
        raise PipelineError(f"Singing transcription failed: {error}") from error

    if not words:
        raise PipelineError(
            "No sung words were detected. Try known-lyrics mode, or use a recording with clearer vocals."
        )
    return words


def _joined_text(words: Sequence[TimedWord]) -> str:
    raw = "".join(word.text for word in words).strip()
    if raw and any(char.isspace() for char in raw):
        return re.sub(r"\s+", " ", raw)
    # Some tokenizers omit leading spaces. Keep punctuation attached to the preceding word.
    result = ""
    for word in words:
        part = word.text.strip()
        if not part:
            continue
        if not result or re.fullmatch(r"[.,!?;:।！？…]+", part):
            result += part
        else:
            result += " " + part
    return result.strip()


def words_to_cues(words: Sequence[TimedWord]) -> list[TimedCue]:
    if not words:
        return []
    cues: list[TimedCue] = []
    group: list[TimedWord] = []

    def flush() -> None:
        if not group:
            return
        text = _joined_text(group)
        if text:
            cues.append(TimedCue(text=text, start=group[0].start, end=group[-1].end))
        group.clear()

    for word in words:
        if group and word.start - group[-1].end >= 0.72:
            flush()
        group.append(word)
        text = _joined_text(group)
        duration = group[-1].end - group[0].start
        terminal = bool(re.search(r"[.!?।！？…][\"'’”)]*$", text))
        natural_segment = word.segment_break and len(group) >= 3 and duration >= 1.8
        if terminal or len(group) >= 12 or duration >= 6.0 or natural_segment:
            flush()
    flush()
    return _sanitize_cues(cues)


def _normalize_token(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold()
    return "".join(char for char in value if char.isalnum())


def _split_long_phrase(text: str, maximum_words: int = 12) -> list[str]:
    words = text.split()
    if len(words) <= maximum_words:
        return [text.strip()] if text.strip() else []
    return [" ".join(words[index : index + maximum_words]) for index in range(0, len(words), maximum_words)]


def split_known_phrases(lyrics: str) -> list[str]:
    phrases: list[str] = []
    for line in lyrics.replace("\r", "\n").split("\n"):
        line = re.sub(r"\s+", " ", line).strip()
        if not line:
            continue
        pieces = re.findall(r".+?(?:[.!?।！？…]+(?=\s|$)|$)", line)
        for piece in pieces:
            phrases.extend(_split_long_phrase(piece.strip()))
    return [phrase for phrase in phrases if phrase]


def _align_known_tokens(known: Sequence[str], recognized: Sequence[TimedWord]) -> list[int | None]:
    n = len(known)
    m = len(recognized)
    if not n or not m:
        return [None] * n
    if n > 3500 or m > 3500:
        raise PipelineError("The lyrics are too long to align safely.")

    known_norm = [_normalize_token(token) for token in known]
    recognized_norm = [_normalize_token(word.text) for word in recognized]
    previous = [float(index) for index in range(m + 1)]
    back = [bytearray(m + 1) for _ in range(n + 1)]
    for column in range(1, m + 1):
        back[0][column] = 2  # Skip a recognized token.

    for row in range(1, n + 1):
        current = [float(row)] + [0.0] * m
        back[row][0] = 1  # A known token has no recognized counterpart.
        for column in range(1, m + 1):
            equal = bool(known_norm[row - 1]) and known_norm[row - 1] == recognized_norm[column - 1]
            diagonal = previous[column - 1] + (0.0 if equal else 1.0)
            missing_known = previous[column] + 1.0
            extra_recognized = current[column - 1] + 1.0
            best = min(diagonal, missing_known, extra_recognized)
            current[column] = best
            back[row][column] = 0 if best == diagonal else (1 if best == missing_known else 2)
        previous = current

    mapping: list[int | None] = [None] * n
    row, column = n, m
    while row > 0 or column > 0:
        direction = back[row][column]
        if row > 0 and column > 0 and direction == 0:
            mapping[row - 1] = column - 1
            row -= 1
            column -= 1
        elif row > 0 and (column == 0 or direction == 1):
            row -= 1
        else:
            column -= 1
    return mapping


def align_known_lyrics(lyrics: str, recognized: Sequence[TimedWord]) -> list[TimedCue]:
    phrases = split_known_phrases(lyrics)
    if not phrases:
        raise PipelineError("No usable pasted lyrics were supplied.")
    phrase_tokens = [phrase.split() for phrase in phrases]
    known_tokens = [token for tokens in phrase_tokens for token in tokens]
    mapping = _align_known_tokens(known_tokens, recognized)
    if not any(index is not None for index in mapping):
        raise PipelineError("The supplied lyrics could not be aligned to the vocals.")

    durations = [word.end - word.start for word in recognized if word.end > word.start]
    typical_duration = median(durations) if durations else 0.35
    centers: list[float | None] = [
        (recognized[index].start + recognized[index].end) / 2 if index is not None else None
        for index in mapping
    ]
    known_indexes = [index for index, center in enumerate(centers) if center is not None]
    first_known = known_indexes[0]
    last_known = known_indexes[-1]
    for index in range(first_known - 1, -1, -1):
        centers[index] = max(0.0, float(centers[index + 1]) - typical_duration)
    for index in range(last_known + 1, len(centers)):
        centers[index] = float(centers[index - 1]) + typical_duration
    for left, right in zip(known_indexes, known_indexes[1:]):
        distance = right - left
        if distance <= 1:
            continue
        start_center = float(centers[left])
        end_center = float(centers[right])
        step = max(0.01, (end_center - start_center) / distance)
        for index in range(left + 1, right):
            centers[index] = start_center + step * (index - left)

    token_starts: list[float] = []
    token_ends: list[float] = []
    for index, center_value in enumerate(centers):
        center = float(center_value)
        direct = mapping[index]
        if direct is not None:
            token_starts.append(recognized[direct].start)
            token_ends.append(recognized[direct].end)
        else:
            previous_center = float(centers[index - 1]) if index > 0 else center - typical_duration
            next_center = float(centers[index + 1]) if index + 1 < len(centers) else center + typical_duration
            token_starts.append(max(0.0, (previous_center + center) / 2))
            token_ends.append(max(center + 0.04, (center + next_center) / 2))

    cues: list[TimedCue] = []
    offset = 0
    for phrase, tokens in zip(phrases, phrase_tokens):
        end_offset = offset + len(tokens) - 1
        cues.append(
            TimedCue(
                text=phrase,
                start=token_starts[offset],
                end=token_ends[end_offset],
            )
        )
        offset = end_offset + 1
    return _sanitize_cues(cues)


def _sanitize_cues(cues: Iterable[TimedCue]) -> list[TimedCue]:
    ordered = sorted((cue for cue in cues if cue.text.strip()), key=lambda cue: cue.start)
    cleaned: list[TimedCue] = []
    for cue in ordered:
        start = max(0.0, cue.start)
        end = max(start + 0.08, cue.end)
        if cleaned and start < cleaned[-1].end:
            previous = cleaned[-1]
            boundary = max(previous.start + 0.08, min(start, previous.end))
            cleaned[-1] = TimedCue(previous.text, previous.start, boundary)
            start = max(start, boundary)
            end = max(start + 0.08, end)
        normalized = re.sub(r"\s+", " ", cue.text).strip()
        if cleaned and _normalize_token(cleaned[-1].text) == _normalize_token(normalized):
            if start - cleaned[-1].end < 0.35:
                continue
        cleaned.append(TimedCue(normalized, start, end))
    return cleaned


def _format_lrc_timestamp(seconds: float) -> str:
    centiseconds = max(0, int(round(seconds * 100.0)))
    minutes, remainder = divmod(centiseconds, 6000)
    whole_seconds, fraction = divmod(remainder, 100)
    return f"[{minutes:02d}:{whole_seconds:02d}.{fraction:02d}]"


def cues_to_lrc(cues: Sequence[TimedCue]) -> str:
    lines: list[str] = []
    for cue in _sanitize_cues(cues):
        start = _format_lrc_timestamp(cue.start)
        end_seconds = max(cue.start + 0.01, cue.end)
        end = _format_lrc_timestamp(end_seconds)
        if end == start:
            end = _format_lrc_timestamp(cue.start + 0.01)
        lines.append(f"{start} {cue.text}")
        lines.append(end)
    return "\n".join(lines)


def process_audio(
    source: Path,
    workspace: Path,
    mode: str,
    language: str,
    known_lyrics: str,
    progress: ProgressCallback,
    register_process: ProcessCallback,
    is_canceled: CancelCallback,
) -> str:
    audio = _prepare_audio(source, workspace, progress, register_process, is_canceled)
    words = _transcribe_words(audio, language, progress, is_canceled)
    _check_canceled(is_canceled)
    progress(90, "Building phrase-level timing")
    if mode == "align":
        cues = align_known_lyrics(known_lyrics, words)
    else:
        cues = words_to_cues(words)
    if not cues:
        raise PipelineError("No synchronized lyric phrases could be produced.")
    lrc = cues_to_lrc(cues)
    if not lrc.strip():
        raise PipelineError("No synchronized lyric phrases could be produced.")
    progress(100, "Synchronized draft ready for review")
    return lrc
