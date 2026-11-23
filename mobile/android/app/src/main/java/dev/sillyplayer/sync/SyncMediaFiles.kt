package dev.sillyplayer.sync

import dev.sillyplayer.data.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

fun selectedSyncTracks(peer: Peer, tracks: List<Track>, memberships: List<Member>): List<Track> {
    val selected =
        JsonCodec.parseToJsonElement(peer.selected)
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
    val ids =
        selected.filter { it.startsWith("track:") }.map { it.removePrefix("track:") }.toMutableSet()
    memberships.filter { "collection:" + it.collection in selected }.forEach { ids += it.track }
    return tracks.filter {
        !it.deleted && it.mergedInto.isBlank() && (peer.allMedia || it.id in ids)
    }
}

private inline fun <T> syncStorage(action: () -> T): T = try {
    action()
} catch (error: IOException) {
    throw IllegalStateException("Cannot access sync storage: ${error.message}", error)
}

fun syncFileHash(file: File, checkCancellation: () -> Unit = {}): String = syncStorage {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            checkCancellation()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** a complete cached chunk is checked before asking for a range at EOF */
suspend fun downloadSyncBlob(
    http: OkHttpClient,
    request: () -> Request,
    hash: String,
    directory: File,
    progress: (Long, Long) -> Unit = { _, _ -> },
): File {
    require(hash.matches(Regex("[a-f0-9]{64}")))
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create sync storage directory" }
    val file = File(directory, "$hash.part")
    val context = currentCoroutineContext()
    if (file.isFile && syncFileHash(file) { context.ensureActive() } == hash) return file
    var lastProgress = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val offset = file.takeIf { it.exists() }?.length() ?: 0L
        syncResponse(http,
            request().newBuilder().header("Range", "bytes=$offset-")
                .header("Accept-Encoding", "identity").build(),
        ) { response ->
                if (response.code == 416 && offset > 0) {
                    // a full retained stage with a bad checksum must not keep asking for an EOF range forever
                    // discard it and fetch from zero
                    check(file.delete()) { "Cannot discard the corrupt sync stage" }
                    return@syncResponse false
                }
                if (response.code != 206)
                    throw SyncHttpException(response.code, "Content transfer failed: HTTP ${response.code}")
                val range =
                    Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                        .matchEntire(response.header("Content-Range").orEmpty())
                        ?: error("Invalid content range")
                val start = range.groupValues[1].toLong()
                val end = range.groupValues[2].toLong()
                val total = range.groupValues[3].toLong()
                require(
                    start == offset && end >= start && end < total && end - start < 8 * 1024 * 1024L
                ) {
                    "Invalid resume range"
                }
                response.body?.byteStream()?.use { input ->
                    val out = syncStorage { FileOutputStream(file, true) }
                    try {
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(file.length() + n <= end + 1) {
                                "Transfer exceeded its declared range"
                            }
                            syncStorage { out.write(buffer, 0, n) }
                            val now = System.nanoTime()
                            if (file.length() == total || now - lastProgress >= 100_000_000) {
                                lastProgress = now
                                progress(file.length(), total)
                            }
                        }
                        syncStorage { out.fd.sync() }
                    } finally {
                        syncStorage { out.close() }
                    }
                } ?: error("Empty transfer response")
                if (file.length() != end + 1) throw EOFException("Content transfer interrupted")
                if (file.length() == total) {
                    if (syncFileHash(file) { context.ensureActive() } != hash) {
                        file.delete()
                        error("Transferred content failed its checksum; tap Sync to retry")
                    }
                    return@syncResponse true
                }
                false
            }.let { complete -> if (complete) return file }
    }
}

/** persistent app storage, independent of cache cleanup or document-picker setup */
fun publishSyncBlob(source: File, directory: File, hash: String, extension: String): File {
    require(hash.matches(Regex("[a-f0-9]{64}")))
    directory.mkdirs()
    val suffix = extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "mp3"
    val target = File(directory, "$hash.$suffix")
    if (target.isFile && syncFileHash(target) == hash) return target
    val stage = File.createTempFile(".sync-", ".part", directory)
    try {
        source.inputStream().use { input ->
            FileOutputStream(stage).use { out ->
                input.copyTo(out, 256 * 1024)
                out.fd.sync()
            }
        }
        // verify the actual staged bytes before publication
        // hashing the source as well would add another complete read without strengthening this check
        require(syncFileHash(stage) == hash) { "Published media checksum mismatch" }
        Files.move(stage.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    } finally {
        stage.delete()
    }
}

fun syncAudioMime(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a",
        "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg",
        "oga",
        "opus" -> "audio/ogg"
        "wav",
        "wave" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
