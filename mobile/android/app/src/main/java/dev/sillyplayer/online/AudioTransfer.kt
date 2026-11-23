package dev.sillyplayer.online

import dev.sillyplayer.sync.SyncHttpException
import dev.sillyplayer.sync.SyncPausedException
import dev.sillyplayer.sync.retrySyncNetwork
import dev.sillyplayer.sync.syncResponse
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

internal class AudioTransferInterruptedException(cause: Exception) : IOException(
    "Connection interrupted. Download progress is kept; tap Retry to continue.", cause,
)

private inline fun <T> audioStorage(action: () -> T): T = try {
    action()
} catch (error: IOException) {
    throw IllegalStateException("Cannot write download storage: ${error.message}", error)
}

/** bounded youtube requests avoid the throttled single large response so other servers use normal GET */
suspend fun transferAudio(http: OkHttpClient, url: String, headers: Map<String, String>, file: File,
    youtube: Boolean, progress: suspend (Long, Long) -> Unit) {
    check(!file.exists() || file.isFile) { "The download destination is not a file" }
    val chunk = 2L * 1024 * 1024
    val validator = File(file.parentFile, file.name + ".etag")
    val source = File(file.parentFile, file.name + ".source")
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun savedValidator(): String? = audioStorage {
        validator.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.startsWith('"') && it.endsWith('"') }
    }
    fun reset() = audioStorage {
        if (file.exists()) FileOutputStream(file, false).close()
        if (validator.exists()) check(validator.delete()) { "Cannot reset the download validator" }
    }
    val previousSource = audioStorage { source.takeIf { it.isFile }?.readText() }
    if (file.length() > 0 && (previousSource != null && previousSource != fingerprint ||
            previousSource == null && savedValidator() == null)) reset()
    audioStorage { source.writeText(fingerprint) } // no signed URL or request credentials on disk
    val transport = if (http.callTimeoutMillis == 0) http else http.newBuilder().callTimeout(0,
        java.util.concurrent.TimeUnit.SECONDS).build()
    var sourceChanges = 0
    try {
        while (true) {
            var retrying = false
            val complete = retrySyncNetwork(onRetry = { retrying = true }) {
                currentCoroutineContext().ensureActive()
                val tag = savedValidator()
                // an ordinary URL without a validator cannot safely append after a failed response
                // youtube's signed source URL identifies its stream
                if (retrying && !youtube && tag == null) reset()
                var done = file.takeIf { it.isFile }?.length() ?: 0L
                val start = done
                val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                headers.forEach { (key, value) -> request.header(key, value) }
                if (youtube || start > 0) {
                    request.header("Range", if (youtube) "bytes=$start-${start + chunk - 1}" else "bytes=$start-")
                    if (tag != null) request.header("If-Range", tag)
                }
                syncResponse(transport, request.build()) { response ->
                    if (response.code == 416 && start > 0) {
                        val size = Regex("bytes \\*/(\\d+)")
                            .matchEntire(response.header("Content-Range").orEmpty())
                            ?.groupValues?.get(1)?.toLongOrNull()
                        if (size == start && tag != null && response.header("ETag") == tag)
                            return@syncResponse true
                        check(++sourceChanges <= 2) { "Cannot resume this audio source; retry the download later" }
                        reset()
                        return@syncResponse false
                    }
                    if (!response.isSuccessful) throw SyncHttpException(response.code, "HTTP ${response.code}")
                    val body = response.body ?: error("Empty audio response")
                    val responseTag = response.header("ETag")
                    if (start > 0 && response.code == 206 && tag != null && responseTag != null && responseTag != tag) {
                        check(++sourceChanges <= 2) { "The audio source keeps changing; retry the download later" }
                        reset()
                        return@syncResponse false
                    }
                    val range = response.header("Content-Range")?.let {
                        Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it)
                    }
                    val total: Long
                    val end: Long?
                    if (response.code == 206) {
                        require(range != null && range.groupValues[1].toLong() == start) { "Invalid audio byte range" }
                        total = range.groupValues[3].toLong()
                        end = range.groupValues[2].toLong()
                        require(end >= start && end < total) { "Invalid audio byte range" }
                    } else {
                        done = 0
                        total = body.contentLength()
                        end = null
                    }
                    audioStorage {
                        if (responseTag != null && !responseTag.startsWith("W/")) validator.writeText(responseTag)
                        else if (response.code != 206 && validator.exists())
                            check(validator.delete()) { "Cannot clear the download validator" }
                    }
                    body.byteStream().use { input ->
                        val out = audioStorage { FileOutputStream(file, done > 0) }
                        try {
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                require(end == null || done + n <= end + 1) { "Audio exceeded its byte range" }
                                audioStorage { out.write(buffer, 0, n) }
                                done += n
                                progress(done, total)
                            }
                            if (end == null || done == total) audioStorage { out.fd.sync() }
                        } finally {
                            audioStorage { out.close() }
                        }
                    }
                    if (end != null && done != end + 1 || end == null && total >= 0 && done != total)
                        throw EOFException("The audio transfer ended early")
                    end == null || done == total
                }
            }
            if (complete) return
        }
    } catch (error: SyncPausedException) {
        throw AudioTransferInterruptedException(error)
    }
}
