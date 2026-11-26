package dev.sillyplayer.data

import java.io.ByteArrayOutputStream
import java.util.Locale

data class FolderCrumb(val name: String, val path: String)

data class FolderEntry(
    val path: String,
    val name: String,
    val trackCount: Int,
    val subfolderCount: Int,
    val cover: String,
)

data class FolderViewData(
    val path: String = "",
    val name: String = "Folders",
    val parentPath: String = "",
    val breadcrumbs: List<FolderCrumb> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val allTracks: List<Track> = emptyList(),
)

// percent-decode without turning literal + into spaces like URLDecoder does
private fun decodeSegment(raw: String): String {
    if ('%' !in raw) return raw
    val out = ByteArrayOutputStream(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = raw[i + 1].digitToIntOrNull(16)
            val lo = raw[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toString(Charsets.UTF_8.name())
}

// derives logical root and subfolder segments from SAF content uris or local file paths
internal fun extractTrackFolderSegments(
    track: Track,
    rootsById: Map<String, Root>,
): Pair<String, List<String>> {
    val root = rootsById[track.root]
    val uri = track.uri
    if (uri.startsWith("content:")) {
        val docPart = uri.substringAfter("/document/", "")
        val treePart = uri.substringAfter("/tree/", "").substringBefore("/document/", "")
        val decodedDoc = if (docPart.isNotEmpty()) decodeSegment(docPart) else ""
        val decodedTree =
            if (treePart.isNotEmpty()) decodeSegment(treePart)
            else root?.uri?.substringAfter("/tree/", "")?.let(::decodeSegment).orEmpty()
        if (decodedDoc.isNotEmpty()) {
            val rootLabel =
                root?.name?.takeIf { it.isNotBlank() }
                    ?: decodedTree.substringAfter(':').substringAfterLast('/').ifBlank {
                        decodedDoc.substringAfter(':').substringBefore('/').ifBlank { "Music" }
                    }
            if (decodedTree.isNotEmpty() && decodedDoc.startsWith(decodedTree)) {
                val rel = decodedDoc.removePrefix(decodedTree).trimStart('/')
                val parts = rel.split('/').filter { it.isNotBlank() }
                val subs = if (parts.size > 1) parts.dropLast(1) else emptyList()
                return rootLabel to subs
            }
            val afterColon = decodedDoc.substringAfter(':', decodedDoc).trim('/')
            val parts = afterColon.split('/').filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val dirs = parts.dropLast(1)
                return dirs.first() to dirs.drop(1)
            }
            return rootLabel to emptyList()
        }
    }
    val plain = uri.removePrefix("file://").trimEnd('/')
    val marker = "/music/"
    val idx = plain.indexOf(marker)
    if (idx >= 0) {
        val rel = plain.substring(idx + marker.length)
        val parts = rel.split('/').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val dirs = parts.dropLast(1)
            return dirs.first() to dirs.drop(1)
        }
        return "Music" to emptyList()
    }
    val parts = plain.split('/').filter { it.isNotBlank() }
    val dirName = if (parts.size >= 2) parts[parts.size - 2] else root?.name?.ifBlank { "Music" } ?: "Music"
    return dirName to emptyList()
}

fun buildAndroidFolderView(
    tracks: List<Track>,
    roots: List<Root>,
    requestedPath: String,
): FolderViewData {
    val rootsById = roots.associateBy { it.id }
    val currentSegs = requestedPath.split('/').filter { it.isNotBlank() }
    val normalizedCurrent = currentSegs.joinToString("/")

    val trackPaths =
        tracks.map { track ->
            val (rootName, subs) = extractTrackFolderSegments(track, rootsById)
            track to (listOf(rootName) + subs)
        }

    val allTracks =
        if (currentSegs.isEmpty()) tracks
        else
            trackPaths
                .filter { (_, segs) ->
                    segs.size >= currentSegs.size &&
                        currentSegs.indices.all { i -> segs[i].equals(currentSegs[i], ignoreCase = true) }
                }
                .map { it.first }

    val directTracks =
        if (currentSegs.isEmpty()) emptyList()
        else
            trackPaths
                .filter { (_, segs) ->
                    segs.size == currentSegs.size &&
                        currentSegs.indices.all { i -> segs[i].equals(currentSegs[i], ignoreCase = true) }
                }
                .map { it.first }
                .sortedBy { it.title.lowercase(Locale.ROOT) }

    class Bucket(val path: String, val name: String) {
        var count = 0
        val nested = HashSet<String>()
        var cover = ""
    }
    val childBuckets = LinkedHashMap<String, Bucket>()
    for ((track, segs) in trackPaths) {
        if (segs.size <= currentSegs.size) continue
        if (currentSegs.indices.any { i -> !segs[i].equals(currentSegs[i], ignoreCase = true) }) continue
        val nextName = segs[currentSegs.size]
        val childKey = nextName.lowercase(Locale.ROOT)
        val childPath = if (normalizedCurrent.isEmpty()) nextName else "$normalizedCurrent/$nextName"
        val bucket = childBuckets.getOrPut(childKey) { Bucket(childPath, nextName) }
        bucket.count++
        if (bucket.cover.isEmpty() && track.art.isNotBlank()) bucket.cover = track.art
        if (segs.size > currentSegs.size + 1) bucket.nested += segs[currentSegs.size + 1].lowercase(Locale.ROOT)
    }

    // include registered roots at the top level even before tracks are added inside them
    if (currentSegs.isEmpty()) {
        for (root in roots) {
            val label = root.name.ifBlank { continue }
            val key = label.lowercase(Locale.ROOT)
            childBuckets.getOrPut(key) { Bucket(label, label) }
        }
    }

    val folders =
        childBuckets.values
            .map { FolderEntry(it.path, it.name, it.count, it.nested.size, it.cover) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    val breadcrumbs =
        if (currentSegs.isEmpty()) emptyList()
        else buildList {
            add(FolderCrumb("Folders", ""))
            var acc = ""
            for (seg in currentSegs) {
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                add(FolderCrumb(seg, acc))
            }
        }
    val parentPath = if (breadcrumbs.size >= 2) breadcrumbs[breadcrumbs.size - 2].path else ""
    val name = currentSegs.lastOrNull() ?: "Folders"

    return FolderViewData(
        path = normalizedCurrent,
        name = name,
        parentPath = parentPath,
        breadcrumbs = breadcrumbs,
        folders = folders,
        tracks = directTracks,
        allTracks = allTracks,
    )
}
