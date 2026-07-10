package dev.lamurbob.youtubedl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

internal data class PublishedDownload(val displayName: String)

internal object DownloadPublisher {
    private const val MIME_TYPE_MP4 = "video/mp4"

    fun publish(context: Context, source: File): PublishedDownload {
        require(source.isFile) { "The completed MP4 file could not be found." }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source)
        } else {
            publishLegacy(context, source)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishWithMediaStore(
        context: Context,
        source: File
    ): PublishedDownload {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE_MP4)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val destination = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Android could not create the file in Downloads.")

        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Android could not open the Downloads file.")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(destination, values, null, null) == 1) {
                "Android could not finish publishing the Downloads file."
            }
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }

        return PublishedDownload(source.name)
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, source: File): PublishedDownload {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        check(downloadsDir.mkdirs() || downloadsDir.isDirectory) {
            "Android could not access the Downloads folder."
        }

        val destination = uniqueDestination(downloadsDir, source.name)
        source.copyTo(destination, overwrite = false)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf(MIME_TYPE_MP4),
            null
        )
        return PublishedDownload(destination.name)
    }

    private fun uniqueDestination(directory: File, originalName: String): File {
        val initial = File(directory, originalName)
        if (!initial.exists()) return initial

        val extensionIndex = originalName.lastIndexOf('.')
        val baseName = if (extensionIndex > 0) {
            originalName.substring(0, extensionIndex)
        } else {
            originalName
        }
        val extension = if (extensionIndex > 0) {
            originalName.substring(extensionIndex)
        } else {
            ""
        }

        var copyNumber = 1
        while (copyNumber < 10_000) {
            val candidate = File(directory, "$baseName ($copyNumber)$extension")
            if (!candidate.exists()) return candidate
            copyNumber += 1
        }

        error("Could not choose a unique filename in Downloads.")
    }
}
