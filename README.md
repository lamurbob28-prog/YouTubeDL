# YouTubeDL

A native Android app for downloading permitted YouTube videos as simple MP4 files.

## Features

- Android app written in Kotlin
- Paste a YouTube link or share a message containing one into the app
- Supports normal YouTube links and YouTube Shorts links
- Strict HTTPS and YouTube-host validation
- Progressive MP4 options up to 360p, 480p, and 720p for easier sharing
- No audio-only mode
- Progress bar with live status
- Stop button with clean cancellation and temporary-file cleanup
- Update button for the bundled yt-dlp runtime
- Publishes completed files into the phone's main `Downloads` folder using Android's supported storage APIs
- Enforces a 4 GB maximum file size
- Shows an **Open Downloads** button after a download finishes
- Hidden debug output that only appears when something fails

## Sharing notes

Use **Discord-ready MP4 up to 360p (most compatible)** first. Every quality option asks yt-dlp for one MP4 file that already contains both video and audio, preferring AVC/AAC for broad playback support. This avoids audio-only results and avoids a separate video/audio merge.

If 360p works, try 480p or 720p. YouTube does not provide every resolution as a combined MP4 for every video, so higher options automatically fall back to the best compatible combined MP4 available.

## Legal use

Use this only for content you are allowed to download, such as your own uploads, videos where you have permission, public-domain videos, or Creative Commons videos where saving/reuse is allowed.

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

Run the validation suite with:

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- Downloads are staged in app-owned storage, then published to the phone's main `Downloads` folder. This works with scoped storage on modern Android.
- Temporary and partial files are removed after success, failure, or cancellation.
- Android 9 and older still request legacy write permission when needed.
- The app accepts HTTPS links only and rejects lookalike hosts.
- If extraction breaks, tap **Update yt-dlp** in the app.
- The app only accepts YouTube / youtu.be URLs and does not use cookies.

## Project layout

```text
app/src/main/java/dev/lamurbob/youtubedl/
  MainActivity.kt       Main screen, download logic, update logic
  DownloadPublisher.kt  Scoped-storage and legacy Downloads publishing
  YoutubeUrlParser.kt   HTTPS host validation and shared-text extraction
  YoutubeDlApp.kt       Initializes yt-dlp
```

## License

MIT
