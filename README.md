# Lyr — Local Music Player with Floating Synced Lyrics

Lyr is a native Android music player that scans music stored on the device, keeps playing through a foreground service, fetches time-synced lyrics from LRCLIB, and displays the current lyric above other apps as a fully transparent floating overlay.

## Features

- Native Kotlin app with XML layouts (no Jetpack Compose)
- Customizable local library with instant List/Grid switching, 2- or 3-column grids, album artwork, duration, ripple feedback, and current-track highlighting
- Three real item styles (flat, elevated rounded, and compact), four sort orders, five bundled fonts, independent Light/Dark/System themes, and preset or custom accent colors
- Persisted in-app song renaming: touch and hold any library song, enter a title, or restore its original MediaStore title later
- MediaStore scanning for MP3, M4A, WAV, and FLAC files
- Correct scoped-storage permissions:
  - Android 13+: `READ_MEDIA_AUDIO`
  - Android 12 and below: `READ_EXTERNAL_STORAGE`
- Persistent mini-player with previous, animated play/pause, next, album artwork, seek control, and visible lyric search/ready/not-found status with retry
- Background playback with `PlayerService`, `MediaPlayer`, audio focus handling, and becoming-noisy protection
- MediaSession notification and lock-screen previous/play-pause/next controls
- Time-synced lyrics with this fallback order:
  1. App's local lyrics cache
  2. LRCLIB public API with cleaned title/artist fallback searches and duration-aware candidate matching
  3. Same-name local `.lrc` file when Android storage access allows it
  4. Visible no-lyrics status with a manual retry action
- Floating `TYPE_APPLICATION_OVERLAY` lyric text with no card or background
- Draggable lyrics with persisted X/Y position
- Fade, scale, and slide lyric animations plus animated color transitions
- One live settings screen for app theme, library layout/style/sort/font/accent and overlay font size/style/color/animation, permission shortcut, and position reset
- All appearance choices persist in one observable preferences manager and apply to the Home screen without restarting
- Graceful handling for denied permissions, unavailable artwork, missing lyrics, network failures, and unreadable files

## Requirements

- Android 7.0 (API 24) or newer
- Android SDK 34 for building
- Java 17
- An Android device or emulator containing supported local audio files

The application ID/package is `com.ahad.lyricsoverlay`.

## Build locally

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build with GitHub Actions

This repository includes `.github/workflows/build-apk.yml`.

1. Open the repository on GitHub.
2. Click **Actions**.
3. Open the latest **Build Android APK** run.
4. Wait until every build step has a green check mark.
5. Scroll to **Artifacts** and download **lyrics-overlay-debug-apk**.
6. Extract the downloaded ZIP and install `app-debug.apk` on the Android device.

Android may ask you to allow installation from the browser or file manager used to open the APK.

## First run

1. Open Lyr and allow access to music/audio files.
2. On Android 13+, allow notifications so playback controls can be shown normally.
3. Tap a song to start playback. If floating lyrics are not allowed yet, use the prompt or the status below the artist to open Android's permission screen.
4. Enable **Display over other apps** for Lyr and return. The current lyric is retried automatically; playback does not need to be restarted.
5. Open the settings button in the top-right corner to customize the Home layout, item style, sorting, theme, accent, app font, and lyric appearance.
6. Touch and hold a song to rename it inside Lyr. Touch and hold it again and choose **Restore original** to remove the override.
7. Drag the lyric text anywhere on screen; its position is saved automatically.

Lyrics are downloaded only when a track needs them. Successful synced lyrics are cached in the app's private storage for future offline playback.

## Architecture

- `MainActivity` — permissions, MediaStore library, song cards, and mini-player UI
- `PlayerService` — foreground playback, queue, MediaSession, notification, audio focus, and lyric timing
- `OverlayService` — transparent draggable lyric overlay and animations
- `SettingsActivity` — persisted/live-applied overlay appearance settings
- `MusicScannerUtil` — local MediaStore audio scanning
- `LyricsRepository` — LRCLIB, cache, and local LRC fallback
- `LrcParser` — timestamp parsing and current-line lookup
- `MusicListAdapter` — song cards and asynchronous album-art decoding

## Privacy and networking

Lyr does not upload audio files. For lyric lookup, it sends the current song title, artist, and duration as query parameters to the public LRCLIB service. Cached lyrics and preferences remain in the app's private local storage.
