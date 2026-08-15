# Lyr — Local Music Player with Floating Synced Lyrics

Lyr is a native Android music player that scans music stored on the device, keeps playing through a foreground service, fetches time-synced lyrics from LRCLIB, and displays the active phrase above other apps as transparent floating text.

## Features

- Native Kotlin app with XML layouts (no Jetpack Compose)
- Customizable local library with instant List/Grid switching, 2- or 3-column grids, artwork, duration, ripple feedback, and current-track highlighting
- Three actual item styles (flat, elevated rounded, and compact), four sort orders, five bundled fonts, independent Light/Dark/System themes, and preset or custom accents
- Persisted in-app song renaming with one-tap restoration of the original MediaStore title
- MediaStore scanning and Android “Open with” support for MP3, M4A, WAV, and FLAC
- Correct scoped-storage permissions (`READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` through Android 12)
- Full Now Playing screen, interactive Up next queue, seek controls, shuffle/repeat, sleep timer, audio focus, becoming-noisy handling, foreground playback, MediaSession notification, and lock-screen controls
- Time-synced lyrics with this source order:
  1. User-edited, AI-reviewed, imported, or explicitly selected lyrics
  2. Downloaded private cache
  3. Same-name local `.lrc` sidecar when storage access allows it
  4. LRCLIB with cleaned searches and duration-aware candidate matching
- Lyrics Center with Live, Online, AI Sync, and Edit sections; line-tap seeking; multiple LRCLIB results; private save/restore; and separately confirmed optional LRCLIB publication
- Bounded duration fitting plus persistent whole-song early/later correction controls
- Fully on-device AI modes:
  - **Audio only → Lyrics + Sync** transcribes the actual recording and builds editable phrase start/end times
  - **Known lyrics → Auto Sync** aligns pasted lines to locally recognized timing
- Automatic RAM-aware multilingual Whisper model choice: compact `base-q5_1` on constrained phones and balanced `small-q5_1` on stronger phones
- One-time resumable model download with exact byte-count and SHA-256 verification; afterward local AI works offline
- Android `MediaExtractor`/`MediaCodec` decoding of MP3, M4A/AAC, WAV, and FLAC to 16 kHz mono PCM entirely on the phone
- Overlapping short inference chunks for truthful progress and cancellation between chunks, with automatic compact-model fallback when memory is tight
- Unsaved synchronized preview, playback verification, word/timestamp editing, and explicit private save before Live Lyrics or overlay use
- Explicit cue ends: lyric text becomes blank in instrumental or vocal gaps instead of lingering until the next phrase
- Manual Bengali/other-language fallback: paste one phrase per line and tap **Sync next line** at each vocal cue
- Transparent, draggable `TYPE_APPLICATION_OVERLAY` text with no card/background, persisted position, and fade/scale/slide animations
- One live settings source for app theme, library layout/style/sort/font/accent, and overlay font size/style/color/animation
- Graceful handling for permissions, missing lyrics, network errors, unreadable audio, insufficient storage, unsupported ABI, cancellation, and low-memory failures

## Requirements

- Android 7.0 (API 24) or newer
- A modern 64-bit ARM Android phone (`arm64-v8a`) for this APK and the bundled on-device Whisper runtime
- Android SDK 34 and Java 17 for building
- A local MP3, M4A, WAV, or FLAC recording
- Internet only for LRCLIB searches and the first AI-model download; no account, server, API key, or hosting is required

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

## First run and on-device AI

1. Open Lyr and allow music/audio access. On Android 13+, allow notifications for normal playback controls.
2. Tap a song, then swipe upward on the artwork/lyrics card (or tap it) to open **Lyrics Center**.
3. Try **Online** first when appropriate. LRCLIB results show recording metadata and duration; explicitly select the matching version.
4. If no lyrics exist, open **AI Sync** and choose **Audio only**. Keep **Prioritize Bengali** enabled for a Bengali song.
5. Lyr displays the model chosen from the phone's reported RAM. Tap **Start AI on this phone** and confirm. The first run downloads and verifies approximately 60 MB (compact) or 190 MB (balanced). An interrupted model download resumes on retry.
6. Lyr decodes the selected song locally, transcribes consecutive overlapping chunks on the phone CPU, and displays real progress. The operation can take longer than the song and can warm the phone; a charger is recommended.
7. Preview the generated phrases during playback. Tap **Review & edit draft**, correct every word and any early/late start or end, then tap **Save and use on this device**. Nothing is saved or published before this step.
8. For known words, choose **Known lyrics**, paste one sung phrase per line, and start local AI. Lyr uses recognition timing while preserving the pasted text.
9. To recover storage, use **Delete downloaded model**. Lyr will automatically choose/download the suitable model again when needed.
10. If an entire result has a constant offset, use **Fix timing**. If only one phrase is wrong, edit its timestamps before saving.
11. Enable **Display over other apps** for transparent floating lyrics. The reviewed AI result uses the same gap-aware timing in Live Lyrics and the overlay.

The manual **Edit / Import** timing workflow remains available and requires no AI model.

## On-device AI design

- `OnDeviceAiLyricsManager` owns model selection, resumable download, SHA-256 verification, progress, cancellation, memory fallback, inference, and draft handoff.
- `LocalAudioDecoder` uses Android's platform codecs and streams directly to a compact 16 kHz mono WAV; song audio is not sent to any network endpoint.
- `WhisperWavChunks` creates one 30-second local chunk at a time with a 2-second overlap and assigns overlap boundaries without duplicated phrases.
- `dev.ffmpegkit-maintained:whisper-android:1.0.0` provides the embedded arm64 whisper.cpp runtime.
- Official multilingual quantized models are fetched directly from the public `ggerganov/whisper.cpp` model repository and stored in app-private files.
- `OnDeviceLyricsProcessor` cleans segments, splits editable phrases, aligns known text with fuzzy Unicode sequence alignment, and emits Lyr's explicit-end LRC representation.
- The model file persists for offline reuse. Decoded WAV/chunk files live only in app cache and are deleted on completion, error, or cancellation.

Singing transcription is harder than ordinary speech. Model quality, phone speed, accompaniment, reverb, and vocal clarity all affect results. The review screen is therefore a required product step, not a claim that every Bengali song will be perfect automatically. Validate words and phrase boundaries on the target phone and recording before relying on the saved overlay.

## Architecture

- `MainActivity` — permissions, MediaStore library, song cards, and mini-player
- `NowPlayingActivity` — full player, seek/timing controls, playback modes, queue, and external audio intents
- `PlayerService` — foreground playback, queue, repeat/shuffle, MediaSession, notification, audio focus, and lyric timing
- `OverlayService` — transparent draggable gap-aware lyric overlay and animations
- `SettingsActivity` / `AppPreferences` — persisted settings with live application
- `MusicScannerUtil` — local MediaStore scanning
- `LyricsRepository` — LRCLIB, cache, provenance, duration fitting, and local LRC fallback
- `OnDeviceAiLyricsManager` / `LocalAudioDecoder` / `WhisperWavChunks` / `OnDeviceLyricsProcessor` — private local AI pipeline
- `LrcParser` — explicit-end timestamp parsing, serialization, fitting, shifting, and active-cue lookup

## Privacy and networking

Normal LRCLIB lookup sends the current title, artist, and duration to the public LRCLIB service. AI Sync does **not** upload the song: only the public Whisper model file is downloaded on first use. Local model inference, audio decoding, transcription, timing, preview, and private save happen on the phone. AI drafts and local edits are never silently published to LRCLIB; public publication remains a separate confirmation that sends song metadata and lyrics, not audio. Cached and privately saved lyrics remain in app-private storage.
