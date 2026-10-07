package dev.lamurbob.youtubedl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    @Volatile private var activeId: String? = null
    private var cancellation = AtomicBoolean(false)
    private var cancelWatcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    private var recovered = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION, notification("Preparing…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        when (intent?.action) {
            ENQUEUE -> {
                val url = YoutubeUrlParser.extractSupportedUrl(intent.getStringExtra("url"))
                val height = intent.getIntExtra("height", 360)
                if (url != null && height in DownloadPolicy.heights) DownloadStore.add(url, height)
                processQueue()
            }
            CANCEL -> {
                val id = intent.getStringExtra("id")
                if (id == activeId) {
                    cancellation.set(true)
                    DownloadStore.update(id.orEmpty()) { it.copy(message = "Stopping…") }
                    // Stop may arrive before execute registers its process; keep
                    // checking until that job exits instead of losing the request.
                    cancelWatcher?.cancel()
                    cancelWatcher = scope.launch(Dispatchers.IO) {
                        while (isActive && activeId == id) {
                            runCatching { YoutubeDL.destroyProcessById(id.orEmpty()) }
                            delay(100)
                        }
                    }
                } else if (id != null) {
                    DownloadStore.update(id) { if (it.status == DownloadStatus.QUEUED)
                        it.copy(status = DownloadStatus.CANCELLED, message = "Cancelled") else it }
                }
                if (worker == null) finishService()
            }
            UPDATE -> if (worker == null) {
                worker = scope.launch {
                    DownloadStore.engineBusy.value = true
                    try {
                        holdWakeLock()
                        updateEngine(force = true)
                    } finally {
                        releaseWakeLock()
                        DownloadStore.engineBusy.value = false
                        worker = null
                        processQueue()
                    }
                }
            }
            else -> if (worker == null) finishService()
        }
        // A killed process is shown as interrupted on next launch; never
        // silently repeat or overwrite the user's completed download.
        return START_NOT_STICKY
    }

    private fun processQueue() {
        if (worker != null) return
        if (DownloadStore.items.value.none { it.status == DownloadStatus.QUEUED }) {
            finishService()
            return
        }
        worker = scope.launch {
            try {
                holdWakeLock()
                if (!recovered) {
                    runInterruptible(Dispatchers.IO) {
                        DownloadPublisher.recoverPending(applicationContext)
                        File(filesDir, "staging").deleteRecursively()
                    }
                    recovered = true
                }
                while (isActive) {
                    val item = DownloadStore.items.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                    download(item)
                }
            } finally {
                releaseWakeLock()
                worker = null
                finishService()
            }
        }
    }

    private suspend fun updateEngine(force: Boolean = false) {
        DownloadStore.engineMessage.value = "Checking for engine updates…"
        try {
            DownloadStore.engineMessage.value = runInterruptible(Dispatchers.IO) {
                DownloadEngine.update(applicationContext, force)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DownloadStore.engineMessage.value = "Update unavailable · using installed engine. ${error.message.orEmpty().take(160)}"
        }
    }

    private suspend fun download(item: DownloadItem) {
        activeId = item.id
        cancellation = AtomicBoolean(false)
        val stopped = cancellation
        val directory = File(filesDir, "staging/${item.id}")
        try {
            DownloadStore.update(item.id) { it.copy(status = DownloadStatus.PREPARING, message = "Preparing engine…") }
            updateNotification("Preparing download…", force = true)
            updateEngine()
            checkStopped(stopped)
            val response = runInterruptible(Dispatchers.IO) {
                DownloadEngine.initialize(applicationContext)
                check(directory.mkdirs() || directory.isDirectory) { "Could not prepare download storage." }
                checkStopped(stopped)
                YoutubeDL.execute(DownloadEngine.request(applicationContext, item, directory), item.id) { progress, _, line ->
                    if (!stopped.get()) {
                        val merging = line.startsWith("[Merger]") || line.startsWith("[VideoRemuxer]")
                        val message = when {
                            merging -> "Combining video and audio…"
                            line.startsWith("[download]") && line.contains('%') -> line.removePrefix("[download]").trim()
                            progress >= 0 -> "Downloading video and audio…"
                            else -> "Reading video information…"
                        }
                        DownloadStore.update(item.id, persist = false) {
                            it.copy(status = DownloadStatus.DOWNLOADING,
                                progress = if (progress >= 0 && !merging) progress.toInt().coerceIn(0, 100) else -1,
                                message = message)
                        }
                        updateNotification("Downloading ${item.height}p", progress.toInt())
                    }
                }
            }
            checkStopped(stopped)
            DownloadStore.update(item.id) { it.copy(status = DownloadStatus.SAVING, progress = -1, message = "Checking video and saving to Downloads…") }
            updateNotification("Saving MP4…", force = true)
            // Treat publication as a commit point. A cancelled Activity or
            // service must not turn a successfully published file into failure.
            withContext(Dispatchers.IO + NonCancellable) {
                val file = DownloadPolicy.completedFile(response.out, directory)
                DownloadEngine.validateVideo(file)
                checkStopped(stopped)
                val published = DownloadPublisher.publish(applicationContext, file) { stopped.get() }
                DownloadStore.update(item.id) { it.copy(status = DownloadStatus.COMPLETE,
                    progress = 100, message = "Saved to Downloads", name = published.displayName, uri = published.uri.toString()) }
            }
        } catch (error: Exception) {
            // Cancellation on returning to Main must not undo a committed save.
            if (DownloadStore.items.value.any { it.id == item.id && it.status == DownloadStatus.COMPLETE }) {
                if (error is CancellationException) throw error
                return
            }
            val cancelled = stopped.get() || error is YoutubeDL.CanceledException
            val interrupted = error is CancellationException && !cancelled
            val details = (error.message ?: error.toString()).takeLast(6000)
            DownloadStore.update(item.id) { it.copy(
                status = when { cancelled -> DownloadStatus.CANCELLED; interrupted -> DownloadStatus.INTERRUPTED; else -> DownloadStatus.FAILED },
                progress = 0,
                message = when { cancelled -> "Cancelled"; interrupted -> "Interrupted. Tap Retry to start again."; else -> DownloadPolicy.friendlyError(details) },
                details = if (cancelled) "" else details)
            }
            if (interrupted) throw error
        } finally {
            cancelWatcher?.cancel()
            activeId = null
            withContext(Dispatchers.IO + NonCancellable) { directory.deleteRecursively() }
        }
    }

    private fun checkStopped(flag: AtomicBoolean) {
        if (flag.get()) throw CancellationException("Stopped")
    }

    private fun notification(text: String, progress: Int = -1): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download).setContentTitle("YouTubeDL")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .apply {
                activeId?.let { id ->
                    val stop = PendingIntent.getService(this@DownloadService, 1,
                        Intent(this@DownloadService, DownloadService::class.java).setAction(CANCEL).putExtra("id", id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, "Stop", stop)
                }
            }.build()
    }

    @Synchronized private fun updateNotification(text: String, progress: Int = -1, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotification < 1000) return
        lastNotification = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text, progress))
    }

    private fun holdWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YouTubeDL:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellation.set(true)
        activeId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
        scope.cancel()
        finishService()
    }

    override fun onDestroy() {
        activeId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
        scope.cancel()
        DownloadStore.engineBusy.value = false
        DownloadStore.items.value.filter { it.status.active }.forEach { item ->
            DownloadStore.update(item.id) { it.copy(status = DownloadStatus.INTERRUPTED,
                message = "Interrupted. Tap Retry to start again.") }
        }
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ENQUEUE = "dev.lamurbob.youtubedl.ENQUEUE"
        const val CANCEL = "dev.lamurbob.youtubedl.CANCEL"
        const val UPDATE = "dev.lamurbob.youtubedl.UPDATE"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 101
    }
}
