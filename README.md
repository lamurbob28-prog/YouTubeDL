# YouTubeDL

A native Android app for downloading YouTube videos that you own, have permission to save, or that are explicitly licensed for reuse, such as public-domain or Creative Commons material.

This is not a pirate cannon with a loading screen. It deliberately avoids cookies, login scraping, DRM bypassing, and private/unlisted access tricks.

## Features

- Android app written in Kotlin
- Paste or share a YouTube link into the app
- Quality picker: best MP4, 720p, 480p, or audio-only M4A
- Progress bar with live yt-dlp output
- Stop button for the active download process
- Update button for the bundled yt-dlp runtime
- Saves into `Downloads/YouTubeDL`
- Built on `youtubedl-android`, which bundles yt-dlp and Python for Android

## Legal use

Use this only for content you are allowed to download:

- Your own uploaded videos
- Videos where the creator gave you permission
- Public-domain videos
- Creative Commons videos where downloading/reuse is allowed
- Other lawful archiving or fair-use scenarios in your jurisdiction

Do not use it to bypass paid access, DRM, login-only content, or copyright restrictions. The app does not ship with cookies or account scraping for a reason, because apparently society collapses when software has a text box.

## Build

### Android Studio

1. Clone the repo.
2. Open the folder in Android Studio.
3. Let Gradle sync.
4. Run the `app` configuration on your phone or emulator.

### Command line

This repo intentionally does not include the Gradle wrapper binary. Use a local Gradle install or Android Studio's Gradle support.

```bash
gradle :app:assembleDebug
```

The debug APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- Android 10+ storage rules are annoying, because even files need bureaucracy now. Downloads are written to `Downloads/YouTubeDL`.
- If extraction breaks, tap **Update yt-dlp** in the app. YouTube changes its internals often enough to qualify as a personality disorder.
- The app only accepts YouTube / youtu.be URLs and does not pass cookies.

## Project layout

```text
app/src/main/java/dev/lamurbob/youtubedl/
  MainActivity.kt      Main screen, download logic, update logic
  YoutubeDlApp.kt      Initializes yt-dlp and FFmpeg
```

## License

MIT
