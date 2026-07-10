package dev.lamurbob.youtubedl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeUrlParserTest {
    @Test
    fun acceptsSupportedHttpsHosts() {
        assertTrue(YoutubeUrlParser.isSupported("https://youtube.com/watch?v=abc"))
        assertTrue(YoutubeUrlParser.isSupported("https://www.youtube.com/shorts/abc"))
        assertTrue(YoutubeUrlParser.isSupported("https://music.youtube.com/watch?v=abc"))
        assertTrue(YoutubeUrlParser.isSupported("https://youtu.be/abc"))
        assertTrue(YoutubeUrlParser.isSupported("https://youtu.be:443/abc"))
    }

    @Test
    fun rejectsInsecureOrLookalikeUrls() {
        assertFalse(YoutubeUrlParser.isSupported("http://youtube.com/watch?v=abc"))
        assertFalse(YoutubeUrlParser.isSupported("https://youtube.com.evil.example/watch?v=abc"))
        assertFalse(YoutubeUrlParser.isSupported("https://youtube.com@evil.example/watch?v=abc"))
        assertFalse(YoutubeUrlParser.isSupported("https://youtube.example/watch?v=abc"))
        assertFalse(YoutubeUrlParser.isSupported("https://youtube.com:444/watch?v=abc"))
    }

    @Test
    fun extractsSupportedUrlFromSharedText() {
        assertEquals(
            "https://youtu.be/abc?si=share",
            YoutubeUrlParser.extractSupportedUrl(
                "Check this out: https://youtu.be/abc?si=share)."
            )
        )
    }

    @Test
    fun keepsAnExactUrlUnchanged() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc&feature=shared",
            YoutubeUrlParser.extractSupportedUrl(
                "  https://www.youtube.com/watch?v=abc&feature=shared  "
            )
        )
    }

    @Test
    fun returnsNullWhenSharedTextHasNoSupportedUrl() {
        assertNull(YoutubeUrlParser.extractSupportedUrl(null))
        assertNull(YoutubeUrlParser.extractSupportedUrl("nothing to download"))
        assertNull(
            YoutubeUrlParser.extractSupportedUrl(
                "Ignore https://youtube.com.evil.example/watch?v=abc"
            )
        )
    }
}
