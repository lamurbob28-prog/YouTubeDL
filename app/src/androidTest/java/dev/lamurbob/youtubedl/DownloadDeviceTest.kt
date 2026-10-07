package dev.lamurbob.youtubedl

import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun sharedLinkAndTypedTextSurviveRotation() {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A video: https://youtu.be/BaW_jenozKc?si=test")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.url_input)
                assertEquals("https://youtu.be/BaW_jenozKc?si=test", input.text.toString())
                input.setText("https://www.youtube.com/shorts/test123")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("https://www.youtube.com/shorts/test123", activity.findViewById<EditText>(R.id.url_input).text.toString())
            }
        }
    }

    @Test fun nativeEngineMergesAndPublishesRealVideoWithAudio() {
        val directory = File(context.cacheDir, "device-integration").apply { mkdirs() }
        val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        pool.submit {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { /* headers */ }
                    val asset = if (path.contains("audio")) "audio.m4a" else "video.mp4"
                    val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }
        }
        try {
            DownloadEngine.initialize(context)
            val formats = JSONArray().apply {
                put(JSONObject().apply {
                    put("format_id", "video"); put("url", "http://127.0.0.1:${server.localPort}/video.mp4")
                    put("ext", "mp4"); put("vcodec", "avc1.4d401f"); put("acodec", "none")
                    put("width", 160); put("height", 90)
                })
                put(JSONObject().apply {
                    put("format_id", "audio"); put("url", "http://127.0.0.1:${server.localPort}/audio.m4a")
                    put("ext", "m4a"); put("vcodec", "none"); put("acodec", "mp4a.40.2")
                })
            }
            val info = File(directory, "fixture.json").apply { writeText(JSONObject().apply {
                put("id", "integration"); put("title", "YouTubeDL device test")
                put("extractor", "generic"); put("webpage_url", "https://example.invalid/fixture")
                put("formats", formats)
            }.toString()) }
            val request = DownloadEngine.configure(YoutubeDLRequest(emptyList()), context, 360, directory)
                .addOption("--load-info-json", info.path)
            val response = YoutubeDL.execute(request, "device-integration")
            val file = DownloadPolicy.completedFile(response.out, directory)
            DownloadEngine.validateVideo(file)
            val result = DownloadPublisher.publish(context, file) { false }
            try {
                assertEquals("video/mp4", context.contentResolver.getType(result.uri))
                context.contentResolver.openInputStream(result.uri)!!.use { assertTrue(it.readBytes().isNotEmpty()) }
            } finally { context.contentResolver.delete(result.uri, null, null) }
        } finally {
            server.close()
            pool.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
