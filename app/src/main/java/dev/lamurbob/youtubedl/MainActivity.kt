package dev.lamurbob.youtubedl

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: TextInputEditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var rightsCheck: CheckBox
    private lateinit var downloadButton: Button
    private lateinit var stopButton: Button
    private lateinit var updateButton: Button
    private lateinit var openDownloadsButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var outputText: TextView

    private var downloading = false
    private val processId = "youtubedl-main-download"

    private val formatOptions = linkedMapOf(
        "Discord-ready MP4 (recommended)" to "bv*[vcodec^=avc1][height<=720][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/b[vcodec^=avc1][acodec^=mp4a][ext=mp4]/best[ext=mp4]/best",
        "Discord-ready MP4 480p" to "bv*[vcodec^=avc1][height<=480][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/b[vcodec^=avc1][height<=480][acodec^=mp4a][ext=mp4]/best[height<=480][ext=mp4]/best[height<=480]",
        "Best MP4 quality" to "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/best[ext=mp4]/best",
        "Small MP4 360p" to "bv*[vcodec^=avc1][height<=360][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/b[vcodec^=avc1][height<=360][acodec^=mp4a][ext=mp4]/best[height<=360][ext=mp4]/best[height<=360]",
        "Audio only M4A" to "bestaudio[ext=m4a]/bestaudio"
    )

    private val progressCallback: (Float, Long, String) -> Unit = { progress, _, line ->
        runOnUiThread {
            progressBar.isIndeterminate = false
            progressBar.progress = progress.toInt().coerceIn(0, 100)
            statusText.text = line.ifBlank { getString(R.string.download_running) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupQualitySpinner()
        setupButtons()
        outputText.movementMethod = ScrollingMovementMethod()
        outputText.visibility = View.GONE
        openDownloadsButton.visibility = View.GONE
        loadUrlFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadUrlFromIntent(intent)
    }

    private fun bindViews() {
        urlInput = findViewById(R.id.url_input)
        qualitySpinner = findViewById(R.id.quality_spinner)
        rightsCheck = findViewById(R.id.rights_check)
        downloadButton = findViewById(R.id.download_button)
        stopButton = findViewById(R.id.stop_button)
        updateButton = findViewById(R.id.update_button)
        openDownloadsButton = findViewById(R.id.open_downloads_button)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        outputText = findViewById(R.id.output_text)
    }

    private fun setupQualitySpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formatOptions.keys.toList()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        qualitySpinner.adapter = adapter
    }

    private fun setupButtons() {
        downloadButton.setOnClickListener { startDownload() }
        stopButton.setOnClickListener { stopDownload() }
        updateButton.setOnClickListener { updateRuntime() }
        openDownloadsButton.setOnClickListener { openDownloads() }
        stopButton.isEnabled = false
    }

    private fun startDownload() {
        if (downloading) {
            toast(getString(R.string.download_already_running))
            return
        }

        if (!ensureStoragePermission()) {
            toast(getString(R.string.storage_permission_needed))
            return
        }

        val url = urlInput.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            urlInput.error = getString(R.string.url_required)
            return
        }

        if (!isSupportedYoutubeUrl(url)) {
            urlInput.error = getString(R.string.url_youtube_only)
            return
        }

        if (!rightsCheck.isChecked) {
            statusText.text = getString(R.string.rights_required)
            return
        }

        val downloadDir = getDownloadLocation()
        val outputTemplate = File(downloadDir, "%(title).120B [%(id)s].%(ext)s").absolutePath
        val selectedFormat = selectedFormat()
        val audioOnly = isAudioOnlySelection()

        outputText.visibility = View.GONE
        openDownloadsButton.visibility = View.GONE
        setDownloadingState(true)
        statusText.text = getString(R.string.download_starting)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    YoutubeDL.init(applicationContext)
                    FFmpeg.init(applicationContext)

                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        addOption("--restrict-filenames")
                        addOption("--newline")
                        addOption("--print", "after_move:filepath")
                        addOption("-f", selectedFormat)
                        addOption("-o", outputTemplate)

                        if (audioOnly) {
                            addOption("-x")
                            addOption("--audio-format", "m4a")
                        } else {
                            addOption("--merge-output-format", "mp4")
                            addOption("--remux-video", "mp4")
                            addOption("--postprocessor-args", "ffmpeg:-movflags +faststart")
                        }
                    }

                    YoutubeDL.execute(request, processId, progressCallback)
                }
            }

            result.onSuccess { response ->
                val downloadedPath = extractDownloadedPath(response.out, downloadDir)
                scanDownloadedFile(downloadedPath)

                progressBar.isIndeterminate = false
                progressBar.progress = 100
                outputText.visibility = View.GONE
                openDownloadsButton.visibility = View.VISIBLE
                statusText.text = if (downloadedPath != null) {
                    getString(R.string.download_complete_file, File(downloadedPath).name)
                } else {
                    getString(R.string.download_complete, downloadDir.absolutePath)
                }
                toast(getString(R.string.download_success))
            }.onFailure { error ->
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                openDownloadsButton.visibility = View.VISIBLE
                statusText.text = getString(R.string.download_failed)
                showOutput(error.message ?: error.toString())
                toast(getString(R.string.download_failed))
            }

            setDownloadingState(false)
        }
    }

    private fun stopDownload() {
        if (!downloading) return

        runCatching {
            YoutubeDL.destroyProcessById(processId)
        }.onFailure { error ->
            showOutput(error.message ?: error.toString())
        }

        statusText.text = getString(R.string.stop_requested)
    }

    private fun updateRuntime() {
        if (downloading) {
            toast(getString(R.string.wait_for_download))
            return
        }

        outputText.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.update_starting)
        updateButton.isEnabled = false

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    YoutubeDL.init(applicationContext)
                    YoutubeDL.updateYoutubeDL(
                        this@MainActivity,
                        YoutubeDL.UpdateChannel.STABLE
                    )
                }
            }

            result.onSuccess { status ->
                val version = runCatching { YoutubeDL.versionName(this@MainActivity) }
                    .getOrDefault(getString(R.string.version_unknown))
                statusText.text = getString(R.string.update_done, status.toString(), version)
                outputText.visibility = View.GONE
            }.onFailure { error ->
                statusText.text = getString(R.string.update_failed)
                showOutput(error.message ?: error.toString())
            }

            progressBar.isIndeterminate = false
            updateButton.isEnabled = true
        }
    }

    private fun selectedFormat(): String {
        val label = qualitySpinner.selectedItem?.toString().orEmpty()
        return formatOptions[label] ?: formatOptions.values.first()
    }

    private fun isAudioOnlySelection(): Boolean {
        return qualitySpinner.selectedItem?.toString()?.contains("Audio only") == true
    }

    private fun ensureStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return true

        val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST
            )
        }

        return granted
    }

    private fun getDownloadLocation(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        return downloadsDir
    }

    private fun setDownloadingState(isDownloading: Boolean) {
        downloading = isDownloading
        downloadButton.isEnabled = !isDownloading
        updateButton.isEnabled = !isDownloading
        stopButton.isEnabled = isDownloading
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = isDownloading
        if (!isDownloading) progressBar.isIndeterminate = false
    }

    private fun showOutput(message: String) {
        outputText.text = message.takeLast(MAX_OUTPUT_CHARS)
        outputText.visibility = View.VISIBLE
    }

    private fun extractDownloadedPath(output: String, downloadDir: File): String? {
        val downloadRoot = downloadDir.absolutePath
        return output
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { line -> line.startsWith(downloadRoot) && File(line).exists() }
    }

    private fun scanDownloadedFile(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        MediaScannerConnection.scanFile(this, arrayOf(filePath), null, null)
    }

    private fun openDownloads() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching {
            startActivity(intent)
        }.onFailure {
            toast(getString(R.string.open_downloads_failed))
        }
    }

    private fun loadUrlFromIntent(intent: Intent?) {
        val sharedUrl = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()

        if (!sharedUrl.isNullOrBlank() && isSupportedYoutubeUrl(sharedUrl)) {
            urlInput.setText(sharedUrl)
        }
    }

    private fun isSupportedYoutubeUrl(rawUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val STORAGE_PERMISSION_REQUEST = 4201
        const val MAX_OUTPUT_CHARS = 4_000
    }
}
