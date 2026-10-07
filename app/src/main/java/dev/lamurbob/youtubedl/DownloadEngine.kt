package dev.lamurbob.youtubedl

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

internal object DownloadEngine {
    fun initialize(context: Context) {
        YoutubeDL.init(context)
        FFmpeg.init(context)
    }

    fun update(context: Context, force: Boolean = false): String {
        initialize(context)
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (force || System.currentTimeMillis() - prefs.getLong("last_check", 0) > 86_400_000) {
            YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        }
        return "Engine ${YoutubeDL.versionName(context) ?: "bundled"} · up to date"
    }

    fun request(context: Context, item: DownloadItem, directory: File) =
        configure(YoutubeDLRequest(item.url), context, item.height, directory)

    internal fun configure(request: YoutubeDLRequest, context: Context, height: Int, directory: File) = request.apply {
        addOption("--ignore-config")
        addOption("--no-playlist")
        addOption("--no-simulate")
        addOption("--no-mtime")
        addOption("--newline")
        addOption("--progress")
        addOption("--no-colors")
        addOption("--restrict-filenames")
        addOption("--print", "after_move:filepath")
        addOption("--match-filter", "!is_live")
        addOption("--max-filesize", DownloadPolicy.MAX_BYTES.toString())
        addOption("--socket-timeout", "20")
        addOption("--retries", "3")
        addOption("--fragment-retries", "3")
        addOption("--abort-on-unavailable-fragments")
        addOption("--concurrent-fragments", "2")
        // The wrapper supplies QuickJS. Fetch matching upstream solver scripts
        // if the installed engine does not bundle a compatible version.
        addOption("--remote-components", "ejs:github")
        addOption("--cache-dir", File(context.cacheDir, "ytdlp").absolutePath)
        addOption("-f", DownloadPolicy.format(height))
        addOption("--merge-output-format", "mp4")
        addOption("--remux-video", "mp4")
        addOption("--postprocessor-args", "Merger+ffmpeg_o:-movflags +faststart")
        addOption("-o", File(directory, "%(title).120B [%(id)s].%(ext)s").absolutePath)
    }

    fun validateVideo(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            check("video/avc" in types && "audio/mp4a-latm" in types) {
                "The file is missing compatible H.264 video or AAC audio. It was not saved."
            }
        } finally {
            extractor.release()
        }
    }
}
