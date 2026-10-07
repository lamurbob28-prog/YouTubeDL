package dev.lamurbob.youtubedl

import java.io.File

internal object DownloadPolicy {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    val heights = listOf(360, 480, 720, 1080)

    fun format(height: Int): String {
        require(height in heights)
        // Bound the shorter dimension, including portrait Shorts. Select only
        // H.264 + AAC; renaming VP9/Opus output to MP4 does not make it compatible.
        val landscape = "[aspect_ratio>=?1][height<=?$height]"
        val portrait = "[aspect_ratio<1][width<=?$height]"
        val video = "bestvideo[ext=mp4][vcodec^=avc1]"
        val audio = "bestaudio[ext=m4a][acodec^=mp4a]"
        val combined = "best[ext=mp4][vcodec^=avc1][acodec^=mp4a]"
        return "$video$landscape+$audio/$video$portrait+$audio/" +
            "$combined$landscape/$combined$portrait"
    }

    fun completedFile(output: String, directory: File): File {
        val root = directory.canonicalFile
        val candidate = output.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }
            .lastOrNull { it.parentFile == root && it.isFile && it.extension == "mp4" }
            ?: error("The downloader did not produce a completed MP4. Update the engine and retry.")
        check(candidate.length() > 0) { "The downloaded file is empty." }
        check(candidate.length() <= MAX_BYTES) { "The completed video exceeds the 4 GB limit." }
        return candidate
    }

    fun friendlyError(details: String): String = when {
        listOf("sign in", "not a bot", "login required", "cookies", "age-restricted")
            .any { details.contains(it, true) } ->
            "YouTube requires sign-in for this video or connection. Try a public video or another network."
        details.contains("private video", true) -> "This video is private. Use a public video."
        details.contains("Requested format", true) ->
            "No compatible MP4 is available at this quality. Try another quality or update the engine."
        details.contains("not available", true) || details.contains("removed", true) ->
            "This video is unavailable or restricted in your region."
        details.contains("No space", true) || details.contains("ENOSPC", true) ->
            "Your phone is out of space. Free some storage and retry."
        details.contains("403") || details.contains("429") ->
            "YouTube refused the connection. Update the engine, wait a little, or try another network."
        details.contains("timed out", true) || details.contains("resolve", true) ->
            "The connection timed out. Check your internet connection and retry."
        else -> "Download failed. Open Details for the reason, or update the engine and retry."
    }
}
