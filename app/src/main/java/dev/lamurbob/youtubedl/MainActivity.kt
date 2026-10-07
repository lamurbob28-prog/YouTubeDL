package dev.lamurbob.youtubedl

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: TextInputEditText
    private lateinit var quality: Spinner
    private lateinit var list: LinearLayout
    private lateinit var downloadButton: Button
    private lateinit var updateButton: Button
    private var pendingDownload: Pair<String, Int>? = null
    private val rows = mutableMapOf<String, View>()
    private val renderedItems = mutableMapOf<String, DownloadItem>()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pending = pendingDownload
        pendingDownload = null
        if (pending != null) {
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                toast("Storage permission is required to save into Downloads on this Android version.")
            } else {
                enqueue(pending.first, pending.second)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, system.bottom)
            insets
        }
        urlInput = findViewById(R.id.url_input)
        quality = findViewById(R.id.quality_spinner)
        list = findViewById(R.id.download_list)
        downloadButton = findViewById(R.id.download_button)
        updateButton = findViewById(R.id.update_button)
        quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("360p · Smaller files", "480p · Balanced", "720p · HD", "1080p · Full HD"))
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val prefs = getPreferences(MODE_PRIVATE)
        quality.setSelection(prefs.getInt("quality", 0).coerceIn(0, 3))
        downloadButton.setOnClickListener { startDownload() }
        updateButton.setOnClickListener { startServiceAction(Intent(this, DownloadService::class.java).setAction(DownloadService.UPDATE)) }
        findViewById<Button>(R.id.paste_button).setOnClickListener {
            val text = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            val url = YoutubeUrlParser.extractSupportedUrl(text)
            if (url != null) { urlInput.setText(url); urlInput.error = null }
            else toast("The clipboard does not contain a supported YouTube link.")
        }
        findViewById<Button>(R.id.open_downloads_button).setOnClickListener {
            launchExternal(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }
        findViewById<Button>(R.id.clear_button).setOnClickListener { DownloadStore.clearFinished() }
        if (savedInstanceState == null) loadIntent(intent)
        pendingDownload = savedInstanceState?.getString("pending_url")?.let {
            it to savedInstanceState.getInt("pending_height", 360)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { DownloadStore.items.collect { render(it) } }
                launch { DownloadStore.engineMessage.collect { findViewById<TextView>(R.id.engine_status).text = it } }
                launch { DownloadStore.engineBusy.collect { updateButtons() } }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDownload?.let { outState.putString("pending_url", it.first); outState.putInt("pending_height", it.second) }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadIntent(intent)
    }

    private fun loadIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        YoutubeUrlParser.extractSupportedUrl(text)?.let { urlInput.setText(it) }
    }

    private fun startDownload() {
        val url = YoutubeUrlParser.extractSupportedUrl(urlInput.text?.toString())
        if (url == null) { urlInput.error = "Paste an HTTPS YouTube video or Shorts link."; return }
        urlInput.error = null
        urlInput.setText(url)
        val height = DownloadPolicy.heights[quality.selectedItemPosition]
        getPreferences(MODE_PRIVATE).edit().putInt("quality", quality.selectedItemPosition).apply()
        requestDownload(url, height)
    }

    private fun requestDownload(url: String, height: Int) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this@MainActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("notification_asked", false)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked", true).apply()
            }
        }
        if (needed.isNotEmpty()) {
            pendingDownload = url to height
            permissions.launch(needed.toTypedArray())
        } else enqueue(url, height)
    }

    private fun enqueue(url: String, height: Int) {
        if (DownloadStore.items.value.count { it.status.active } >= 20) {
            toast("The queue is full. Wait for a download to finish."); return
        }
        startServiceAction(Intent(this, DownloadService::class.java).setAction(DownloadService.ENQUEUE)
            .putExtra("url", url).putExtra("height", height))
    }

    private fun startServiceAction(intent: Intent) {
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { toast("Could not start the download service: ${it.message}") }
    }

    private fun updateButtons() {
        val active = DownloadStore.items.value.any { it.status.active }
        updateButton.isEnabled = !active && !DownloadStore.engineBusy.value
        downloadButton.text = if (active) "Add to queue" else "Download MP4"
    }

    private fun render(items: List<DownloadItem>) {
        updateButtons()
        val ordered = items.filter { it.status.active } + items.filterNot { it.status.active }.reversed()
        val ids = ordered.map { it.id }
        rows.keys.filterNot { it in ids }.toList().forEach { id ->
            list.removeView(rows.remove(id)); renderedItems.remove(id)
        }
        ordered.forEachIndexed { index, item ->
            val row = rows.getOrPut(item.id) { layoutInflater.inflate(R.layout.download_item, list, false) }
            if (list.indexOfChild(row) != index) {
                list.removeView(row)
                list.addView(row, index)
            }
            if (renderedItems[item.id] != item) {
                bindRow(row, item)
                renderedItems[item.id] = item
            }
        }
        findViewById<View>(R.id.empty_state).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.clear_button).isEnabled = items.any { !it.status.active }
        findViewById<TextView>(R.id.queue_summary).text = when (val count = items.count { it.status.active }) {
            0 -> "Your downloads"
            1 -> "1 download in progress"
            else -> "$count downloads in queue"
        }
    }

    private fun bindRow(row: View, item: DownloadItem) {
        row.findViewById<TextView>(R.id.item_title).text = item.name.ifBlank { item.url }
        row.findViewById<TextView>(R.id.item_badge).text = "${item.height}p · MP4 · ${item.status.name.lowercase().replaceFirstChar { it.uppercase() }}"
        row.findViewById<TextView>(R.id.item_status).text = item.message
        row.findViewById<LinearProgressIndicator>(R.id.item_progress).apply {
            visibility = if (item.status.active) View.VISIBLE else View.GONE
            isIndeterminate = item.progress < 0
            if (item.progress >= 0) progress = item.progress
        }
        val primary = row.findViewById<Button>(R.id.item_primary)
        val secondary = row.findViewById<Button>(R.id.item_secondary)
        val details = row.findViewById<Button>(R.id.item_details)
        secondary.visibility = View.GONE
        primary.isEnabled = true
        when {
            item.status == DownloadStatus.COMPLETE -> {
                primary.text = "Play"
                primary.setOnClickListener { openFile(item, false) }
                secondary.visibility = View.VISIBLE
                secondary.text = "Share"
                secondary.setOnClickListener { openFile(item, true) }
            }
            item.status.active -> {
                primary.text = if (item.status == DownloadStatus.QUEUED) "Remove" else "Stop"
                primary.setOnClickListener { startServiceAction(Intent(this, DownloadService::class.java)
                    .setAction(DownloadService.CANCEL).putExtra("id", item.id)) }
            }
            else -> {
                primary.text = "Retry"
                primary.setOnClickListener { requestDownload(item.url, item.height) }
            }
        }
        details.visibility = if (item.details.isBlank()) View.GONE else View.VISIBLE
        details.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Download details").setMessage(item.details)
                .setPositiveButton("Close", null).setNeutralButton("Copy") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Download details", item.details))
                }.show()
        }
    }

    private fun openFile(item: DownloadItem, share: Boolean) {
        val uri = Uri.parse(item.uri)
        val exists = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        if (!exists) { toast("This file was moved or deleted. Download it again."); return }
        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(item.name, uri)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchExternal(if (share) Intent.createChooser(intent, "Share MP4") else intent)
    }

    private fun launchExternal(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("No app is available to open this file or folder.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
