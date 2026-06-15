package dev.lamurbob.youtubedl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var outputText: TextView

    private var downloading = false
    private val processId = "youtubedl-main-download"

    private val formatOptions = linkedMapOf(
        "Best MP4 video" to "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
        "720p MP4" to "bv*[height<=720][ext=mp4]+ba[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]",
        "480p MP4" to "bv*[height<=480][ext=mp4]+ba[ext=m4a]/best[height<=480][ext=mp4]/best[height<=480]",
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
        updateButton.setOnClickListener { updateYtDlp() }
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
        val outputTemplate = File(downloadDir, "%(title).160B [%(id)s].%(ext)s").absolutePath
        val selectedFormat = selectedFormat()
        val audioOnly = selectedFormat == formatOptions["Audio only M4A"]

        setDownloadingState(true)
        statusText.text = getString(R.string.download_starting)
        outputText.text = getString(R.string.output_waiting)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)

                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        addOption("--restrict-filenames")
                        addOption("--newline")
                        addOption("-o", outputTemplate)

                        if (audioOnly) {
                            addOption("-f", selectedFormat)
                            addOption("-x")
                            addOption("--audio-format", "m4a")
                        } else {
                            addOption("-f", selectedFormat)
                            addOption("--merge-output-format", "mp4")
                        }
                    }

                    YoutubeDL.getInstance().execute(request, processId, progressCallback)
                }
            }

            result.onSuccess { response ->
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                statusText.text = getString(R.string.download_complete, downloadDir.absolutePath)
                outputText.text = response.out.takeLast(MAX_OUTPUT_CHARS).ifBlank {
                    getString(R.string.output_empty)
                }
                toast(getString(R.string.download_success))
            }.onFailure { error ->
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = getString(R.string.download_failed)
                outputText.text = error.message ?: error.toString()
                toast(getString(R.string.download_failed))
            }

            setDownloadingState(false)
        }
    }

    private fun stopDownload() {
        if (!downloading) return

        runCatching {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }.onFailure { error ->
            outputText.text = error.message ?: error.toString()
        }

        statusText.text = getString(R.string.stop_requested)
    }

    private fun updateYtDlp() {
        if (downloading) {
            toast(getString(R.string.wait_for_download))
            return
        }

        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.update_starting)
        updateButton.isEnabled = false

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(applicationContext)
                    YoutubeDL.getInstance().updateYoutubeDL(
                        this@MainActivity,
                        YoutubeDL.UpdateChannel._STABLE
                    )
                }
            }

            result.onSuccess { status ->
                val version = runCatching { YoutubeDL.getInstance().versionName(this@MainActivity) }
                    .getOrDefault(getString(R.string.version_unknown))
                statusText.text = getString(R.string.update_done, status.toString(), version)
                outputText.text = status.toString()
            }.onFailure { error ->
                statusText.text = getString(R.string.update_failed)
                outputText.text = error.message ?: error.toString()
            }

            progressBar.isIndeterminate = false
            updateButton.isEnabled = true
        }
    }

    private fun selectedFormat(): String {
        val label = qualitySpinner.selectedItem?.toString().orEmpty()
        return formatOptions[label] ?: formatOptions.values.first()
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
        return File(downloadsDir, "YouTubeDL").apply {
            if (!exists()) mkdirs()
        }
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
