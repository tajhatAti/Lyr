# Lyr — Local Music Player with Floating Synced Lyrics

Lyr is a native Android music player that scans music stored on the device, keeps playing through a foreground service, fetches time-synced lyrics from LRCLIB, and displays the active phrase above other apps as transparent floating text.

## Features

- Native Kotlin app with XML layouts (no Jetpack Compose)
- Customizable local library with instant List/Grid switching, 2- or 3-column grids, artwork, duration, ripple feedback, and current-track highlighting
- Three actual item styles (flat, elevated rounded, and compact), four sort orders, ten bundled Latin/Bengali-capable font styles, independent Light/Dark/System themes, and preset or custom accents
- Persisted in-app song renaming with one-tap restoration of the original MediaStore title; a verified recognized identity can improve malformed metadata without overriding an explicit rename
- MediaStore scanning and Android “Open with” support for MP3, M4A, WAV, and FLAC
- Correct scoped-storage permissions (`READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` through Android 12)
- Full Now Playing screen, interactive Up next queue, seek controls, shuffle/repeat, sleep timer, audio focus, becoming-noisy handling, foreground playback, MediaSession notification, and lock-screen controls
- A song tap starts playback and the complete lyrics workflow automatically:
  1. Keep an explicit user version or verified private cache.
  2. Search LRCLIB by cleaned metadata with strict title, artist, duration, and native-script checks.
  3. If metadata is unreliable, listen to short useful sections locally, send only recognized text to privacy-safe lyric/community searches, resolve any title/artist hint back through LRCLIB, and validate the full recognized-word evidence.
  4. Try a same-name local `.lrc` sidecar, then finish local transcription/alignment only when no trustworthy synchronized result exists.
  5. Persist and apply the result automatically to Live Lyrics and the overlay.
- Lyrics Center remains an optional inspection/correction surface with Live, Online, AI Sync, and Edit sections; line-tap seeking; multiple LRCLIB results; private save/restore; and separately confirmed optional LRCLIB publication
- Bounded duration fitting plus persistent whole-song early/later correction controls
- Fully on-device AI modes:
  - **Audio only → Lyrics + Sync** transcribes the actual recording and builds editable phrase start/end times
  - **Known lyrics → Auto Sync** aligns pasted lines to locally recognized timing
- Speed-first multilingual `base-q5_1` model on every supported phone instead of selecting the much slower `small` model on high-RAM devices
- One-time resumable model download with exact byte-count and SHA-256 verification; afterward local AI works offline
- Native-script policy: Bengali metadata/lyrics select Bengali transcription with translation disabled, romanized automatic matches are rejected, and English/unknown metadata uses automatic language detection
- Android `MediaExtractor`/`MediaCodec` decoding of MP3, M4A/AAC, WAV, and FLAC to 16 kHz mono PCM entirely on the phone
- Verse/chorus-oriented sample chunks trigger an early text-only LRCLIB retry; all remaining overlapping chunks run only when retrieval fails
- Hard 8-minute Smart Lyrics limit before online/AI work, with decoded-duration verification for files whose stored duration is unavailable; longer story/podcast-style audio uses saved/cache/sidecar lyrics only and shows a clear explanation
- Automatic durable result adoption plus optional synchronized preview, playback verification, word/timestamp editing, and private corrections
- Explicit cue ends: lyric text becomes blank in instrumental or vocal gaps instead of lingering until the next phrase
- Optional advanced tools remain available for **Audio only → Lyrics + Sync**, **Paste lyrics → Auto Sync**, and manual cue capture
- Transparent, draggable `TYPE_APPLICATION_OVERLAY` text with no card/background, persisted position, Bengali fonts, and none/fade/scale/slide/rise/pop/flip animations
- One live settings source for app theme, library layout/style/sort/font/accent, and overlay font size/style/color/animation
- Graceful handling for permissions, missing lyrics, network errors, unreadable audio, insufficient storage, unsupported ABI, cancellation, and low-memory failures

## Requirements

- Android 7.0 (API 24) or newer
- A modern 64-bit ARM Android phone (`arm64-v8a`) for this APK and the bundled on-device Whisper runtime
- Android SDK 34 and Java 17 for building
- A local MP3, M4A, WAV, or FLAC recording
- Internet only for LRCLIB/Genius text searches and the first AI-model download; no account, server, API key, audio upload, or hosting is required

The application ID/package is `com.ahad.lyricsoverlay`.

## Build locally

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` also runs the JVM regression tests.

## Build with GitHub Actions

The repository includes `.github/workflows/build-apk.yml`.

1. Open the repository on GitHub.
2. Click **Actions**.
3. Open the latest **Build Android APK** run.
4. Wait until every build step has a green check mark.
5. Scroll to **Artifacts** and download **lyrics-overlay-debug-apk**.
6. Extract the ZIP and install `app-debug.apk` on the Android phone.

Android may ask you to allow installation from the browser or file manager used to open the APK.

## First run and automatic Smart Lyrics

1. Open Lyr and allow music/audio access. On Android 13+, allow notifications for playback and background Smart Lyrics progress.
2. Tap a song. Playback and Smart Lyrics start together; no search, AI, review, or save button is required.
3. Lyr first checks private/cache data and searches LRCLIB with the available title, artist, album, and duration. Materially different-duration or wrong-script matches are rejected.
4. When metadata cannot produce a trustworthy result, Lyr locally decodes useful song sections and listens on the phone. It may send a few recognized words—not audio—to LRCLIB and Genius text search to discover identity clues, then resolves those clues through LRCLIB and validates duration, native script, and full recognized-word overlap.
5. Only after online and same-folder LRC fallbacks fail does Lyr download/verify the approximately 60 MB multilingual model if needed and complete local transcription. Downloads and song checkpoints resume after ordinary process interruption.
6. The winning synchronized result is saved and applied automatically. Bengali results must contain Bengali Unicode; translation is disabled. Explicit instrumental gaps remain blank in Live Lyrics and the floating overlay.
7. Audio over 8 minutes is never searched, identified, decoded, or transcribed by Smart Lyrics. Lyr only reuses lyrics already on the phone and explains that the recording may be long-form audio.
8. Open **Lyrics Center** only when you want to inspect, correct, replace, import, publish with confirmation, align pasted words, or run the advanced audio-only tool yourself.
9. Use **Delete downloaded model** in AI Sync to recover storage. Use **Fix timing** for a constant whole-song offset, or edit individual cue times for a local correction.
10. Enable **Display over other apps** for transparent floating lyrics.

The manual **Edit / Import** timing workflow remains available and requires no AI model.

## On-device AI design

- `PlayerService` automatically orchestrates metadata lookup, one active local job, latest-song queueing, foreground progress, durable result adoption, and playback/overlay refresh.
- `OnDeviceAiLyricsManager` owns the 8-minute preflight, speed-first model, resumable download, SHA-256 verification, early recognized-text retry, progress, cancellation, inference, and checkpointed result handoff.
- `LocalAudioDecoder` uses Android's platform codecs and streams directly to a compact 16 kHz mono WAV; song audio is not sent to any network endpoint.
- `WhisperWavChunks` prioritizes two likely verse/chorus regions, then covers the full recording in 30-second local chunks with a 2-second overlap and unambiguous boundary ownership.
- `dev.ffmpegkit-maintained:whisper-android:1.0.0` provides the embedded arm64 whisper.cpp runtime.
- Official multilingual quantized models are fetched directly from the public `ggerganov/whisper.cpp` model repository and stored in app-private files.
- `OnDeviceLyricsProcessor` cleans segments, splits editable phrases, aligns known text with fuzzy Unicode sequence alignment, and emits Lyr's explicit-end LRC representation.
- The model file persists for offline reuse. Decoded WAV/chunk files live only in app cache and are deleted on completion, error, or cancellation.

Singing transcription is harder than ordinary speech. Model quality, phone speed, accompaniment, reverb, and vocal clarity all affect results. Lyr applies its best validated result automatically, but Lyrics Center remains available when a particular recording needs correction. Whisper exposes segment intervals rather than exact phoneme timestamps, so phrase boundaries are conservative and must not be interpreted as guaranteed word- or syllable-level precision.

## Architecture

- `MainActivity` — permissions, MediaStore library, song cards, and mini-player
- `NowPlayingActivity` — full player, seek/timing controls, playback modes, queue, and external audio intents
- `PlayerService` — foreground playback, queue, repeat/shuffle, MediaSession, notification, audio focus, and lyric timing
- `OverlayService` — transparent draggable gap-aware lyric overlay and animations
- `SettingsActivity` / `AppPreferences` — persisted settings with live application
- `MusicScannerUtil` — local MediaStore scanning
- `LyricsRepository` — LRCLIB, conservative Genius identity hints, cache, provenance, duration/script/content validation, fitting, and local LRC fallback
- `OnDeviceAiLyricsManager` / `LocalAudioDecoder` / `WhisperWavChunks` / `OnDeviceLyricsProcessor` — private local AI pipeline
- `LrcParser` — explicit-end timestamp parsing, serialization, fitting, shifting, and active-cue lookup

## Privacy and networking

Normal LRCLIB lookup sends the current title, artist, and duration to the public LRCLIB service. Smart Lyrics does **not** upload the song: the public Whisper model is downloaded on first use, and an early retry may send a few locally recognized text phrases to LRCLIB and Genius text search. Genius is used only for a conservative title/artist hint; synchronized lyrics still come from LRCLIB and must pass local duration, script, and recognized-content validation. Local model inference, audio decoding, transcription, timing, preview, and private save happen on the phone. AI results and local edits are never silently published to LRCLIB; public publication remains a separate confirmation that sends song metadata and lyrics, not audio. Cached and privately saved lyrics remain in app-private storage.
