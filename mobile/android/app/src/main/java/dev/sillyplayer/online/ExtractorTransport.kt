package dev.sillyplayer.online

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

// newPipe parses desktop pages
// an Android UA sends SoundCloud to its mobile next.js site, which has none of the scripts used to discover its client ID
internal const val EXTRACTOR_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal class ExtractorTransport(private val http: OkHttpClient) : Downloader() {
    private class Session {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val cancelled = AtomicBoolean(false)
    }
    private val active = ThreadLocal<Session>()

    /** cancelling a lookup closes its sockets, including during body reads */
    suspend fun <T> lookup(block: () -> T): T = try { withTimeout(90_000) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val session = Session()
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        session.cancelled.set(true)
                        session.calls.forEach { it.cancel() }
                    }
                }
                try {
                    ensureActive()
                    active.set(session)
                    block().also { ensureActive() }
                } catch (error: IOException) {
                    ensureActive()
                    throw error
                } finally {
                    active.remove()
                    cancellation.cancel()
                }
            }
        }
    } } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("Media lookup timed out. Check the connection and retry.", error)
    }

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url()).header("User-Agent", EXTRACTOR_USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        builder.method(request.httpMethod(), request.dataToSend()?.toRequestBody(null))
        return withResponse(builder.build()) { response ->
            val data = response.body?.source()?.let {
                it.request(16 * 1024 * 1024L + 1)
                require(it.buffer.size <= 16 * 1024 * 1024L) { "Extractor response exceeded 16 MiB" }
                it.readUtf8()
            }.orEmpty()
            if (response.code == 429) throw IOException("Service rate limit reached (HTTP 429). Wait and retry.")
            if (response.code >= 400) throw IOException("Media service returned HTTP ${response.code}")
            Response(response.code, response.message, response.headers.toMultimap(), data, response.request.url.toString())
        }
    }

    fun <T> withResponse(request: okhttp3.Request, read: (okhttp3.Response) -> T): T {
        val call = http.newCall(request)
        val session = active.get()
        session?.calls?.add(call)
        if (session?.cancelled?.get() == true) call.cancel()
        try {
            return call.execute().use(read)
        } finally { session?.calls?.remove(call) }
    }
}
