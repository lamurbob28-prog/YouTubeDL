package dev.lamurbob.youtubedl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DownloadCopyTest {
    @Test fun copiesBytesWithoutChangingVideo() {
        val bytes = ByteArray(150_000) { (it % 256).toByte() }
        val destination = ByteArrayOutputStream()
        DownloadCopy.copy(ByteArrayInputStream(bytes), destination, { false })
        assertArrayEquals(bytes, destination.toByteArray())
    }

    @Test fun cancellationStopsDuringCopy() {
        val destination = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            DownloadCopy.copy(ByteArrayInputStream(ByteArray(150_000)), destination, { destination.size() > 0 })
        }
        assertEquals(64 * 1024, destination.size())
    }

    @Test fun limitRejectsBeforeWritingTooManyBytes() {
        val destination = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            DownloadCopy.copy(ByteArrayInputStream(ByteArray(100)), destination, { false }, limit = 99)
        }
        assertEquals(0, destination.size())
    }
}
