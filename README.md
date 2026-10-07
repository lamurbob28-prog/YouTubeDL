# YouTubeDL for Android

A native Android downloader for public YouTube videos and Shorts. Version **0.3.0** adds a background queue, a redesigned screen, automatic engine updates, and compatible MP4 merging.

## What changed

- **Modern downloads:** choose up to 360p, 480p, 720p, or 1080p. Download H.264 video and AAC audio, then merge them into one MP4 with initialized FFmpeg. Compatible combined streams remain a fallback; there are no hardcoded format IDs or audio-only modes.
- **Engine maintenance:** checks the stable yt-dlp channel before a download, at most once daily after a successful check. Update engine forces a check. Failed update checks keep the installed engine available. The Android wrapper includes QuickJS, and matching EJS scripts can be fetched from upstream GitHub when needed.
- **Background queue:** up to 20 queued videos, ongoing progress notification and Stop action, independent of the screen. Rotation and switching apps keep downloads running. Android can still stop an app; interrupted jobs show Retry after relaunch instead of silently redownloading.
- **Useful history:** the latest 50 finished records with Play, Share, Retry, and copyable error details. Clear history removes records, never downloaded videos.
- **Safer output:** check for both H.264 and AAC tracks before publishing to Android Downloads. Enforce a 4 GiB final-file limit. Cancelled or failed jobs clean staging and partial publication. Pending MediaStore entries are recovered after interruption.
- **Cleaner interface:** dark layout, large controls, clipboard paste, saved quality choice, queue progress, and direct sharing with the correct `video/mp4` MIME type.

## Install

Open [Android CI](https://github.com/lamurbob28-prog/YouTubeDL/actions/workflows/android.yml), choose the latest successful run for `main`, and download **YouTubeDL-debug-apk** under Artifacts. Extract the ZIP and install its APK on Android 7 or newer. Android may ask to allow installation from your browser or file manager.

These are development APKs. An older APK signed with a different debug key cannot be updated in place; Android reports a signature conflict. Export any app data you need before uninstalling an old build. Videos already saved in Downloads remain outside app storage.

## Use

1. Paste an HTTPS YouTube link, paste a message containing one, or use **Share → YouTubeDL** from YouTube.
2. Select quality and tap **Download MP4**. Add more links to the queue while it runs.
3. Use **Play**, **Share**, or **Open Downloads** once it finishes.

Notification permission is optional on Android 13+. On Android 9 and older, allow storage access to save into Downloads. Downloads use a foreground data-sync service and a bounded wake lock. Force stopping the app ends downloads; Retry starts interrupted jobs again. Queued jobs are not automatically resumed after process death.

Files contain AVC/H.264 video and AAC audio for broad playback compatibility. Discord and other apps impose their own upload limits; **this app does not promise a particular file size or an inline embed**. YouTube may offer a lower resolution than selected. Live streams are excluded. Private, age-restricted, region-blocked, or sign-in-required videos may not download; cookies and account-login bypasses are not implemented.

## Build and verify

Use Java 17, Android SDK 35, and Gradle 8.10.2 (Android Studio or a local Gradle installation). The existing repository does not include a Gradle wrapper.

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same gates and uploads the APK and verification reports. Unit tests cover link validation, output containment, empty/oversized results, cancellation, and publication copy limits. The format selection integration test exercises yt-dlp against local fixture metadata without contacting YouTube.

On a device, verify a public regular video and a Short, background/rotate during a download, stop an active download, remove a queued job, deny notification permission, and Play/Share the output. A successful build is not proof that every YouTube video or network is supported.

## Development layout

- `MainActivity.kt`: input, queue/history cards, permissions and sharing.
- `DownloadService.kt`: foreground queue, cancellation, wake lock and notifications.
- `DownloadEngine.kt`: initialization, updates, requests and codec validation.
- `DownloadPublisher.kt`: scoped-storage publishing, recovery and legacy sharing.
- `DownloadStore.kt`: observable state and persisted history.
- `DownloadPolicy.kt`, `DownloadCopy.kt`, `YoutubeUrlParser.kt`: tested policies and helpers.

Download only material you own or have permission to save. MIT license applies to app source; bundled libraries retain their own licenses.
