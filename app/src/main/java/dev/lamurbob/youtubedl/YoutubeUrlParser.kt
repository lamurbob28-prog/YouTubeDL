package dev.lamurbob.youtubedl

import java.net.URI
import java.util.Locale

internal object YoutubeUrlParser {
    private val urlCandidate = Regex(
        pattern = """https://[^\s<>"']+""",
        option = RegexOption.IGNORE_CASE
    )
    private val trailingPunctuation = setOf('.', ',', ';', ':', '!', ')', ']', '}')

    fun isSupported(rawUrl: String): Boolean {
        val candidate = rawUrl.trim()
        if (candidate.isEmpty()) return false

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false

        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com")
    }

    fun extractSupportedUrl(sharedText: String?): String? {
        val text = sharedText?.trim().orEmpty()
        if (isSupported(text)) return text

        return urlCandidate
            .findAll(text)
            .map { match -> match.value.trimEnd { it in trailingPunctuation } }
            .firstOrNull(::isSupported)
    }
}
