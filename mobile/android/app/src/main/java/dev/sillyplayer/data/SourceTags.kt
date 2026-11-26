package dev.sillyplayer.data

import java.net.URI
import java.security.MessageDigest

data class ExtractedSource(
    val url: String,
    val provider: String,
    val itemId: String,
    val uploader: String = "",
    val title: String = "",
)

private val YT_ID_RE = Regex("^[A-Za-z0-9_-]{11}$")
private val YT_PATH_ID_RE = Regex("^/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})(?:/|$)")
private val SILLY_COMMENT_RE =
    Regex("""^(https?://\S+)\s*·\s*Uploader:\s*(.+?)\s*·\s*Original title:\s*(.+)$""")
private val ANY_URL_RE = Regex("""(https?://[^\s<>"'\)\]]+)""")
private val FILE_YT_ID_RE = Regex("""\[([A-Za-z0-9_-]{11})\](?:\s+[A-Za-z0-9-]{6,40})?$""")

// pull youtube/soundcloud links back out of embedded tags or yt-dlp filenames on local scans
internal fun extractYouTubeVideoIdFromUrl(input: String): String {
    val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return ""
    val host = uri.host?.lowercase() ?: return ""
    if (host == "youtu.be") {
        val seg = uri.path.orEmpty().removePrefix("/").substringBefore('/')
        return if (YT_ID_RE.matches(seg)) seg else ""
    }
    if ("youtube.com" in host) {
        val query = uri.rawQuery.orEmpty()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == "v") {
                val v = part.substring(eq + 1)
                if (YT_ID_RE.matches(v)) return v
            }
        }
        val m = YT_PATH_ID_RE.find(uri.path.orEmpty())
        if (m != null) return m.groupValues[1]
    }
    return ""
}

private fun directIdHash(url: String): String {
    val clean = url.substringBefore('#')
    return MessageDigest.getInstance("SHA-256")
        .digest(clean.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun extractSourceMetadata(tagValues: List<String>, fileName: String): ExtractedSource? {
    var sourceUrl = ""
    var provider = ""
    var itemId = ""
    var uploader = ""
    var videoTitle = ""

    for (raw in tagValues) {
        val text = raw.trim()
        if (text.isEmpty()) continue
        var candidateUrl = ""
        val silly = SILLY_COMMENT_RE.matchEntire(text)
        if (silly != null) {
            candidateUrl = silly.groupValues[1].trim()
            val u = silly.groupValues[2].trim()
            val t = silly.groupValues[3].trim()
            if (uploader.isEmpty() && u.isNotEmpty() && !u.equals("Unknown", ignoreCase = true)) {
                uploader = u
            }
            if (videoTitle.isEmpty() && t.isNotEmpty()) {
                videoTitle = t
            }
        } else {
            val found = ANY_URL_RE.find(text)
            if (found != null) candidateUrl = found.groupValues[1].trim()
        }
        if (candidateUrl.isEmpty()) continue
        val host = runCatching { URI(candidateUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host == "youtu.be" || host.endsWith("youtube.com")) {
            val vid = extractYouTubeVideoIdFromUrl(candidateUrl)
            if (vid.isNotEmpty()) {
                sourceUrl = "https://www.youtube.com/watch?v=$vid"
                provider = "youtube"
                itemId = vid
                break
            }
        } else if ((host == "soundcloud.com" || host.endsWith(".soundcloud.com") || host == "snd.sc") && sourceUrl.isEmpty()) {
            sourceUrl = candidateUrl
            provider = "soundcloud"
            itemId = directIdHash(candidateUrl)
        }
    }

    if (sourceUrl.isEmpty() && fileName.isNotBlank()) {
        val stem = fileName.substringBeforeLast('.')
        val fm = FILE_YT_ID_RE.find(stem)
        if (fm != null) {
            val vid = fm.groupValues[1]
            sourceUrl = "https://www.youtube.com/watch?v=$vid"
            provider = "youtube"
            itemId = vid
        }
    }

    if (sourceUrl.isEmpty()) return null
    return ExtractedSource(sourceUrl, provider, itemId, uploader, videoTitle)
}
