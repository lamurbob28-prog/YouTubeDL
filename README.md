# YouTubeDL

A native Android app for downloading permitted YouTube videos as simple MP4 files.

## Features

- Android app written in Kotlin
- Paste or share a YouTube link into the app
- Supports normal YouTube links and YouTube Shorts links
- MP4-only video options for easier sharing
- No audio-only mode
- Progress bar with live status
- Stop button for the active download process
- Update button for the bundled yt-dlp runtime
- Saves directly into the phone's main `Downloads` folder
- Shows an **Open Downloads** button after a download finishes
- Hidden debug output that only appears when something fails

## Sharing notes

Use **Discord MP4 360p (safest)** first. It asks yt-dlp for one MP4 file that already contains both video and audio. That avoids the previous audio-only/extract behavior.

If 360p works, try 480p or 720p. If a higher option fails, the source video may not offer that exact single-file MP4 format.

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

The debug APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- Downloads are written directly to the phone's main `Downloads` folder.
- After a file finishes, the app asks Android's media scanner to index it and shows an **Open Downloads** button.
- If extraction breaks, tap **Update yt-dlp** in the app.
- The app only accepts YouTube / youtu.be URLs and does not use cookies.

## Project layout

```text
app/src/main/java/dev/lamurbob/youtubedl/
  MainActivity.kt      Main screen, download logic, update logic
  YoutubeDlApp.kt      Initializes yt-dlp
```

## License

MIT
