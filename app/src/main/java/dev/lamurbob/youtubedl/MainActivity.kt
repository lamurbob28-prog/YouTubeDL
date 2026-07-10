package dev.lamurbob.youtubedl

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    @Volatile
    private var stopRequested = false
    private val processId = "youtubedl-main-download"

    private val formatOptions = linkedMapOf(
        "Discord-ready MP4 up to 360p (most compatible)" to
            "best[ext=mp4][vcodec^=avc1][acodec^=mp4a][height<=360]/" +
            "best[ext=mp4][vcodec!=none][acodec!=none][height<=360]/18",
        "Discord-ready MP4 up to 480p" to
            "best[ext=mp4][vcodec^=avc1][acodec^=mp4a][height<=480]/" +
            "best[ext=mp4][vcodec!=none][acodec!=none][height<=480]/18",
        "Discord-ready MP4 up to 720p" to
            "best[ext=mp4][vcodec^=avc1][acodec^=mp4a][height<=720]/" +
            "best[ext=mp4][vcodec!=none][acodec!=none][height<=720]/22/18"
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

        val url = urlInput.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            urlInput.error = getString(R.string.url_required)
            return
        }

        if (!YoutubeUrlParser.isSupported(url)) {
            urlInput.error = getString(R.string.url_youtube_only)
            return
        }
        urlInput.error = null

        if (!rightsCheck.isChecked) {
            statusText.text = getString(R.string.rights_required)
            return
        }

        if (!ensureStoragePermission()) {
            toast(getString(R.string.storage_permission_needed))
            return
        }

        val workDir = runCatching { createDownloadWorkDirectory() }.getOrElse { error ->
            statusText.text = getString(R.string.storage_setup_failed)
            showOutput(error.message ?: error.toString())
            return
        }
        val outputTemplate = File(workDir, "%(title).120B [%(id)s].%(ext)s").absolutePath
        val selectedFormat = selectedFormat()

        stopRequested = false
        outputText.visibility = View.GONE
        openDownloadsButton.visibility = View.GONE
        setDownloadingState(true)
        statusText.text = getString(R.string.download_starting)

        lifecycleScope.launch {
            var completedDownload: PublishedDownload? = null
            var failure: Exception? = null

            try {
                val response = withContext(Dispatchers.IO) {
                    YoutubeDL.init(applicationContext)

                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        addOption("--restrict-filenames")
                        addOption("--newline")
                        addOption("--print", "after_move:filepath")
                        addOption("--max-filesize", "4G")
                        addOption("--socket-timeout", "30")
                        addOption("--retries", "3")
                        addOption("-f", selectedFormat)
                        addOption("-o", outputTemplate)
                    }

                    YoutubeDL.execute(request, processId, progressCallback)
                }

                if (!stopRequested) {
                    completedDownload = withContext(Dispatchers.IO) {
                        val downloadedFile = extractDownloadedFile(response.out, workDir)
                            ?: throw IllegalStateException(
                                getString(R.string.download_file_missing)
                            )
                        DownloadPublisher.publish(applicationContext, downloadedFile)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            } finally {
                withContext(Dispatchers.IO + NonCancellable) {
                    workDir.deleteRecursively()
                }
            }

            val publishedDownload = completedDownload
            when {
                stopRequested -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    outputText.visibility = View.GONE
                    openDownloadsButton.visibility = View.GONE
                    statusText.text = getString(R.string.download_stopped)
                    toast(getString(R.string.download_stopped))
                }
                publishedDownload != null -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    outputText.visibility = View.GONE
                    openDownloadsButton.visibility = View.VISIBLE
                    statusText.text = getString(
                        R.string.download_complete_file,
                        publishedDownload.displayName
                    )
                    toast(getString(R.string.download_success))
                }
                else -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    openDownloadsButton.visibility = View.GONE
                    statusText.text = getString(R.string.download_failed)
                    val error = failure
                    showOutput(error?.message ?: getString(R.string.output_empty))
                    toast(getString(R.string.download_failed))
                }
            }

            setDownloadingState(false)
            stopRequested = false
        }
    }

    private fun stopDownload() {
        if (!downloading) return

        stopRequested = true
        stopButton.isEnabled = false
        runCatching {
            YoutubeDL.destroyProcessById(processId)
        }.onFailure { error ->
            showOutput(error.message ?: error.toString())
        }

        statusText.text = getString(R.string.stop_requested)
    }

    override fun onDestroy() {
        if (downloading) {
            stopRequested = true
            runCatching { YoutubeDL.destroyProcessById(processId) }
        }
        super.onDestroy()
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

    private fun createDownloadWorkDirectory(): File {
        val appDownloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val workDir = File(appDownloads, "staging/${UUID.randomUUID()}")
        check(workDir.mkdirs() || workDir.isDirectory) {
            getString(R.string.storage_setup_failed)
        }
        return workDir
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

    private fun extractDownloadedFile(output: String, downloadDir: File): File? {
        val downloadRoot = runCatching { downloadDir.canonicalFile }.getOrNull() ?: return null
        return output
            .lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                runCatching { File(line).canonicalFile }.getOrNull()
            }
            .lastOrNull { file ->
                file.isFile &&
                    file.parentFile == downloadRoot &&
                    file.extension.equals("mp4", ignoreCase = true)
            }
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
        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        val sharedUrl = YoutubeUrlParser.extractSupportedUrl(sharedText)
        if (sharedUrl != null) {
            urlInput.setText(sharedUrl)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val STORAGE_PERMISSION_REQUEST = 4201
        const val MAX_OUTPUT_CHARS = 4_000
    }
}
