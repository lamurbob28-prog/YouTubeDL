package dev.lamurbob.youtubedl

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the actual yt-dlp selector against fixtures. No YouTube/network requests. */
class FormatIntegrationTest {
    private val executable = System.getenv("YTDLP_TEST_BIN")
    private fun select(formats: String, height: Int): Pair<Int, String> {
        assumeTrue("Set YTDLP_TEST_BIN to run the integration fixtures", !executable.isNullOrBlank())
        val input = Files.createTempFile("formats", ".json").toFile()
        try {
            input.writeText("""{"id":"fixture","title":"Fixture","extractor":"generic","webpage_url":"https://example.invalid/test","formats":[$formats]}""")
            val process = ProcessBuilder(executable!!, "--ignore-config", "--simulate", "--no-warnings", "--load-info-json", input.path,
                "-f", DownloadPolicy.format(height), "--print", "%(format_id)s").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            return process.waitFor() to text
        } finally { input.delete() }
    }

    private fun video(id: String, width: Int, height: Int, codec: String = "avc1.4d401f") =
        """{"format_id":"$id","url":"https://example.invalid/$id.mp4","ext":"mp4","vcodec":"$codec","acodec":"none","width":$width,"height":$height,"tbr":1000}"""
    private val audio = """{"format_id":"aac","url":"https://example.invalid/a.m4a","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128}"""

    @Test fun splitOnlyVideoNowWorksAtRequestedQuality() {
        val (code, selected) = select(listOf(video("360", 640, 360), video("720", 1280, 720), video("1080", 1920, 1080), audio).joinToString(","), 720)
        assertEquals(selected, 0, code)
        assertEquals("720+aac", selected)
    }

    @Test fun choosesShortsByShorterDimension() {
        val (code, selected) = select(listOf(video("short144", 144, 256), video("short360", 360, 640), video("short720", 720, 1280), audio).joinToString(","), 360)
        assertEquals(selected, 0, code)
        assertEquals("short360+aac", selected)
    }

    @Test fun fallsBackToCombinedMp4WithoutFixedFormatIds() {
        val (code, selected) = select("""{"format_id":"combined","url":"https://example.invalid/c.mp4","ext":"mp4","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":360,"width":640}""", 720)
        assertEquals(selected, 0, code)
        assertEquals("combined", selected)
    }

    @Test fun refusesAudioOnlyAndIncompatibleCodecs() {
        assertNotEquals(0, select(audio, 360).first)
        assertNotEquals(0, select(video("av1", 640, 360, "av01.0.00M.08") + "," + audio, 360).first)
    }
}
