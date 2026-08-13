# Lyrics Overlay (Android)

`com.ahad.lyricsoverlay` package-এর একটি native Kotlin Android app। অন্য app খোলা থাকলেও এটি draggable floating window-এ user-provided lyrics দেখায়।

## Features

- `WindowManager` + `TYPE_APPLICATION_OVERLAY` (Android 8.0+)
- Android 7.0/7.1-এর জন্য `TYPE_PHONE` fallback, কারণ `TYPE_APPLICATION_OVERLAY` API 26-এ যোগ হয়েছে
- `SYSTEM_ALERT_WINDOW` settings flow
- Background operation-এর জন্য foreground service ও persistent notification
- Android 14-এর `specialUse` foreground-service declaration
- প্রতি line change-এ animated color, fade ও scale transition
- Draggable overlay, previous/next, pause/resume এবং close controls
- Plain lyrics এবং LRC timestamp-যুক্ত lines গ্রহণ করে (timestamp বাদ দিয়ে দেখায়)
- `minSdk 24`, `targetSdk 34`, `compileSdk 34`
- GitHub Actions থেকে installable debug APK artifact

> এই project Spotify/Musixmatch-এর private lyrics database বা playback API ব্যবহার করে না। Main screen-এ দেওয়া lyrics নির্ধারিত interval অনুযায়ী overlay-তে দেখায়।

## দরকারি ফাইল

- `app/src/main/java/com/ahad/lyricsoverlay/MainActivity.kt` — permission ও input screen
- `app/src/main/java/com/ahad/lyricsoverlay/LyricsOverlayService.kt` — foreground service, overlay ও animations
- `app/src/main/AndroidManifest.xml` — permissions/service declarations
- `.github/workflows/build-apk.yml` — GitHub Actions APK build

## Local build

Java 17 এবং Android SDK 34 প্রয়োজন।

```bash
./gradlew assembleDebug
```

APK পাওয়া যাবে:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions দিয়ে APK

1. GitHub repository-এর **Actions** tab খুলুন।
2. **Build Android APK** workflow নির্বাচন করুন।
3. **Run workflow** চাপুন (অথবা push করলে নিজে থেকেই চলবে)।
4. কাজ শেষ হলে run-এর **Artifacts** section থেকে `lyrics-overlay-debug-apk` download করুন।
5. ZIP extract করে `app-debug.apk` Android device-এ install করুন।

Debug APK install করতে signing secret লাগে না। Play Store/release distribution-এর জন্য আলাদা release keystore নিরাপদ GitHub Secrets-এ রাখতে হবে; repository-তে keystore বা password commit করবেন না।

## ব্যবহার

1. App install করে খুলুন।
2. **Overlay permission দিন** চাপুন এবং “Display over other apps” allow করুন।
3. Lyrics paste করুন এবং প্রতি line-এর interval দিন।
4. **Overlay চালু** চাপুন। Android 13+ হলে notification permission prompt-ও আসতে পারে।
5. অন্য app খুলুন; floating card স্ক্রিনের উপর থাকবে। Card-এর header ধরে drag করা যাবে।

## Security note

এই Android project বানানোর আগে repository-তে hard-coded credentials-সহ পুরোনো scripts ছিল। Working tree থেকে সেগুলো সরানো হয়েছে, কিন্তু Git history থেকে file delete করলেই কোনো প্রকাশিত credential নিরাপদ হয় না। সংশ্লিষ্ট credentials অবিলম্বে revoke/rotate করুন এবং প্রয়োজন হলে repository history আলাদাভাবে rewrite করুন।
