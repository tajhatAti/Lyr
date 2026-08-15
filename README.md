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
- Dedicated full-screen Now Playing experience with large artwork, elapsed/remaining time, scrubbing, previous/play/next, persisted shuffle/repeat modes, and an interactive Up next library queue
- Android “Open with” integration for audio files shared by a file manager or another app, including metadata/artwork playback without requiring a full library scan
- Background playback with `PlayerService`, `MediaPlayer`, audio focus handling, and becoming-noisy protection
- MediaSession notification and lock-screen previous/play-pause/next controls that reopen the full player
- Time-synced lyrics with this authoritative fallback order:
  1. User-edited, imported, or explicitly selected lyrics
  2. Downloaded private cache
  3. Same-name local `.lrc` sidecar when Android storage access allows it
  4. LRCLIB public API with cleaned title/artist fallback searches and duration-aware candidate matching
- A three-section Lyrics Center for live line-tap seeking, multiple LRCLIB results, offline selection, `.lrc` import, editing, private save/restore, and separately confirmed optional publication
- Bounded automatic timestamp fitting when the selected LRCLIB recording and local song have different durations, plus persistent whole-song early/later correction controls
- A plain-lyrics workflow for Bengali and other unavailable songs: paste one lyric per line, play the song, and tap **Sync next line** at each vocal cue
- A service-owned sleep timer with presets, custom minutes, end-of-current-song mode, persistent status, and cancellation
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
3. Tap a song to open the full player. Scrub through the song, change tracks, enable shuffle/repeat, or choose any song from **Up next**.
4. Swipe upward on the artwork/lyrics card (or tap the card) to open **Lyrics Center**. Tap a synchronized line to seek. Use **Online** to inspect and choose another recording when matching is wrong.
5. If lyrics progressively drift because the LRCLIB recording has a different duration, choose that version again so bounded duration fitting is applied. Use **Fix timing** for a remaining constant early/late offset.
6. For a song unavailable online, open **Edit / Import**, paste ordinary lyrics one line at a time, start playback, and tap **Sync next line** whenever each line starts; then save privately.
7. You can also tap an MP3, M4A, WAV, or FLAC in a file manager and choose **Play with Lyr Music** from Android's app chooser.
8. If floating lyrics are not allowed yet, use the prompt or the status below the artist to open Android's permission screen. Enable **Display over other apps** for Lyr and return; playback does not need to be restarted.
9. Open the settings button in the top-right corner to customize the Home layout, item style, sorting, theme, accent, app font, and lyric appearance.
10. Touch and hold a song to rename it inside Lyr. Touch and hold it again and choose **Restore original** to remove the override.
11. Drag the lyric text anywhere on screen; its position is saved automatically.

Lyrics are downloaded only when a track needs them. Successful synced lyrics are cached in the app's private storage for future offline playback.

## Architecture

- `MainActivity` — permissions, MediaStore library, song cards, and mini-player UI
- `NowPlayingActivity` — full player, seek/timing controls, playback modes, queue UI, and external audio intents
- `PlayerService` — foreground playback, queue, repeat/shuffle behavior, MediaSession, notification, audio focus, and lyric timing
- `OverlayService` — transparent draggable lyric overlay and animations
- `SettingsActivity` — persisted/live-applied overlay appearance settings
- `MusicScannerUtil` — local MediaStore audio scanning
- `LyricsRepository` — LRCLIB, cache, and local LRC fallback
- `LrcParser` — timestamp parsing and current-line lookup
- `MusicListAdapter` — song cards and asynchronous album-art decoding

## Privacy and networking

Lyr does not upload audio files. For lyric lookup, it sends the current song title, artist, and duration as query parameters to the public LRCLIB service. Cached lyrics and preferences remain in the app's private local storage.
