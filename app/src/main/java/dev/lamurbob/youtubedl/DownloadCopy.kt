package dev.lamurbob.youtubedl

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

internal object DownloadCopy {
    fun copy(input: InputStream, output: OutputStream, cancelled: () -> Boolean, limit: Long = DownloadPolicy.MAX_BYTES) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw CancellationException("Stopped")
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= limit) { "The completed video exceeds the 4 GB limit." }
            output.write(buffer, 0, count)
        }
        output.flush()
    }
}
