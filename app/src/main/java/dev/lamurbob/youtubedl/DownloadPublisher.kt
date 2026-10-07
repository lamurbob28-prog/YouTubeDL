package dev.lamurbob.youtubedl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException

internal data class PublishedDownload(val displayName: String, val uri: Uri)

internal object DownloadPublisher {
    fun publish(context: Context, source: File, cancelled: () -> Boolean): PublishedDownload {
        require(source.isFile && source.length() > 0) { "The completed MP4 could not be found." }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source, cancelled)
        } else {
            publishLegacy(context, source, cancelled)
        }
    }

    fun recoverPending(context: Context) {
        val prefs = context.getSharedPreferences("publishing", Context.MODE_PRIVATE)
        prefs.getString("pending_uri", null)?.let { raw ->
            // A pending entry is always one created by this app. Never delete
            // visible files or scan the user's Downloads folder for cleanup.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val uri = Uri.parse(raw)
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst() && cursor.getInt(0) == 1) {
                                context.contentResolver.delete(uri, null, null)
                            }
                        }
                }
            }
        }
        prefs.edit().remove("pending_uri").apply()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishWithMediaStore(context: Context, source: File, cancelled: () -> Boolean): PublishedDownload {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create the file in Downloads.")
        val prefs = context.getSharedPreferences("publishing", Context.MODE_PRIVATE)
        prefs.edit().putString("pending_uri", destination.toString()).commit()
        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().buffered().use { input ->
                    DownloadCopy.copy(input, output, cancelled)
                }
            } ?: error("Android could not open the Downloads file.")
            if (cancelled()) throw CancellationException("Stopped")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(destination, values, null, null) == 1) {
                "Android could not finish saving the file."
            }
        } catch (error: Exception) {
            runCatching { resolver.delete(destination, null, null) }
            throw error
        } finally {
            prefs.edit().remove("pending_uri").commit()
        }
        val name = resolver.query(destination, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: source.name
        return PublishedDownload(name, destination)
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, source: File, cancelled: () -> Boolean): PublishedDownload {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(directory.mkdirs() || directory.isDirectory) { "Android could not access Downloads." }
        val destination = uniqueDestination(directory, source.name)
        try {
            source.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output -> DownloadCopy.copy(input, output, cancelled) }
            }
            if (cancelled()) throw CancellationException("Stopped")
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("video/mp4"), null)
        return PublishedDownload(destination.name, FileProvider.getUriForFile(context, "${context.packageName}.files", destination))
    }

    private fun uniqueDestination(directory: File, name: String): File {
        for (number in 0 until 10_000) {
            val candidate = File(directory, if (number == 0) name else "${name.substringBeforeLast('.')} ($number).mp4")
            if (candidate.createNewFile()) return candidate
        }
        error("Could not choose a unique filename in Downloads.")
    }
}
