package dev.sillyplayer.sync

import dev.sillyplayer.data.JsonCodec
import dev.sillyplayer.data.Outbox
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val SYNC_BATCH_OPERATIONS = 100
internal const val SYNC_BATCH_BYTES = 1024 * 1024
private const val SYNC_REQUEST_BYTES = 8 * 1024 * 1024

internal data class SyncBatch(val entries: List<Outbox>, val body: JsonObject)

/** the target is soft for a single large playlist order so the protocol ceiling is hard (bricked up even) */
internal fun nextSyncBatch(pending: List<Outbox>): SyncBatch {
    val entries = ArrayList<Outbox>(minOf(pending.size, SYNC_BATCH_OPERATIONS))
    val operations = ArrayList<JsonElement>(entries.size)
    var bytes = "{\"operations\":[]}".toByteArray().size
    for (entry in pending) {
        if (entries.size == SYNC_BATCH_OPERATIONS) break
        val wire = buildJsonObject {
            put("opId", entry.opId)
            put("entity", entry.entity)
            put("key", entry.key)
            put("base", entry.base)
            put("payload", entry.payload?.let { JsonCodec.parseToJsonElement(it) } ?: JsonNull)
        }
        val size = wire.toString().toByteArray(Charsets.UTF_8).size
        require(size + 32 <= SYNC_REQUEST_BYTES) {
            "A ${entry.entity} change is too large to synchronize. Reduce its playlist size and retry."
        }
        val next = bytes + size + if (entries.isEmpty()) 0 else 1
        if (entries.isNotEmpty() && next > SYNC_BATCH_BYTES) break
        entries += entry
        operations += wire
        bytes = next
    }
    return SyncBatch(entries, buildJsonObject { put("operations", JsonArray(operations)) })
}

internal class SyncHttpException(val status: Int, message: String) : IOException(message)
internal class SyncPausedException(cause: Exception) : IOException(
    "Connection interrupted. Progress is saved; tap Resume to continue.", cause,
)

internal fun transientSyncFailure(error: Exception): Boolean {
    if (error is SyncHttpException) return error.status in setOf(408, 429, 500, 502, 503, 504)
    if (error !is IOException || error is SyncPausedException) return false
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is SSLException || cause is CertificateException) return false
        cause = cause.cause
    }
    return true
}

internal suspend fun <T> retrySyncNetwork(
    onRetry: (Int) -> Unit = {},
    wait: suspend (Long) -> Unit = { delay(it) },
    operation: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            return operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!transientSyncFailure(error)) throw error
            if (attempt == 5) throw SyncPausedException(error)
            onRetry(++attempt)
            val base = minOf(15_000L, 1_000L shl (attempt - 1))
            wait((base * Random.nextDouble(.8, 1.2)).toLong().coerceAtMost(15_000))
        }
    }
}

/** cancellation covers both waiting for headers and streaming the body */
internal suspend fun <T> syncResponse(
    http: OkHttpClient,
    request: Request,
    consume: suspend (Response) -> T,
): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val call = http.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { consume(it) }
        } catch (error: IOException) {
            // OkHttp reports a cancelled socket as IOException
            // preserve coroutine cancellation so it cannot fail a parent scope or become a retry
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellation.cancel()
        }
    }
}

internal fun syncJson(response: Response): JsonObject {
    val text = response.body?.string().orEmpty()
    val document = runCatching { JsonCodec.parseToJsonElement(text) as? JsonObject }.getOrNull()
    if (!response.isSuccessful) {
        throw SyncHttpException(response.code,
            document?.get("error")?.jsonPrimitive?.contentOrNull ?: "Sync HTTP ${response.code}")
    }
    require(document != null) { "Desktop returned an invalid sync response" }
    document["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
    return document
}
