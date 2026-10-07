package dev.lamurbob.youtubedl

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun acceptsOnlyCompletedNonemptyMp4InsideStaging() {
        val root = Files.createTempDirectory("download-test").toFile()
        try {
            val output = File(root, "video.mp4").apply { writeText("test video") }
            File(root, "video.mp4.part").writeText("partial")
            assertEquals(output.canonicalFile, DownloadPolicy.completedFile("[download] done\n${output.absolutePath}\n", root))
            assertThrows(IllegalStateException::class.java) {
                DownloadPolicy.completedFile(File(root, "video.mp4.part").absolutePath, root)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesOutsidePathsEmptyFilesAndOversizeFiles() {
        val parent = Files.createTempDirectory("download-test").toFile()
        val root = File(parent, "staging").apply { mkdir() }
        try {
            val outside = File(parent, "outside.mp4").apply { writeText("test") }
            assertThrows(IllegalStateException::class.java) { DownloadPolicy.completedFile(outside.path, root) }
            val inside = File(root, "inside.mp4").apply { createNewFile() }
            assertThrows(IllegalStateException::class.java) { DownloadPolicy.completedFile(inside.path, root) }
            java.io.RandomAccessFile(inside, "rw").use { it.setLength(DownloadPolicy.MAX_BYTES + 1) }
            assertThrows(IllegalStateException::class.java) { DownloadPolicy.completedFile(inside.path, root) }
        } finally { parent.deleteRecursively() }
    }

    @Test fun rejectsUnsupportedQuality() {
        assertThrows(IllegalArgumentException::class.java) { DownloadPolicy.format(0) }
    }

    @Test fun givesActionableNetworkAndStorageErrors() {
        assertTrue(DownloadPolicy.friendlyError("HTTP Error 403: Forbidden").contains("refused"))
        assertTrue(DownloadPolicy.friendlyError("No space left on device").contains("out of space"))
        assertTrue(DownloadPolicy.friendlyError("Sign in to confirm you're not a bot").contains("sign-in"))
    }
}
