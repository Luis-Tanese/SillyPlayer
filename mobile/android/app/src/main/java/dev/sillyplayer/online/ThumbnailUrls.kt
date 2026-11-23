package dev.sillyplayer.online

private val youtubeImage = Regex("^https?://(?:i[0-9]?\\.ytimg\\.com|img\\.youtube\\.com)/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/")
private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
private val soundCloudImage = Regex("^https?://(?:[^/]+\\.)?sndcdn\\.com/")
private val soundCloudSize = Regex("-(?:original|t[0-9]+x[0-9]+|large|crop|small)(?=\\.[a-zA-Z]+(?:\\?|$))")

/** keep the provider URL so later attempts can still use its signature or original size */
fun sourceThumbnail(raw: String, youtubeId: String): String {
    val url = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
    return if (url.startsWith("https://") || url.startsWith("http://")) url
    else thumbnailCandidates("", youtubeId).firstOrNull().orEmpty()
}

fun thumbnailCandidates(source: String, youtubeId: String = ""): List<String> {
    val url = source.trim().let { if (it.startsWith("//")) "https:$it" else it }
    val id = youtubeImage.find(url)?.groupValues?.get(1) ?: youtubeId
    if (videoId.matches(id)) {
        val base = "https://i.ytimg.com/vi/$id"
        return buildList {
            add("$base/hqdefault.jpg")
            if (url.startsWith("https://") || url.startsWith("http://")) add(url)
            add("$base/mqdefault.jpg")
            add("$base/default.jpg")
        }.distinct()
    }
    if (url.isBlank()) return emptyList()
    if (soundCloudImage.containsMatchIn(url)) {
        val secure = url.replaceFirst("http://", "https://")
        val sized = secure.replace(soundCloudSize, "-t300x300")
        return listOf(sized, secure, sized.replace("-t300x300.", "-large.")).distinct()
    }
    return listOf(url)
}
