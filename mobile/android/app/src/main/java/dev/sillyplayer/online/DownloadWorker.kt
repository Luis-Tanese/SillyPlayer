package dev.sillyplayer.online

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.*
import dev.sillyplayer.R
import dev.sillyplayer.data.*
import dev.sillyplayer.graph
import dev.sillyplayer.media.NativeMedia
import java.io.*
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Request

fun Context.certificates(): File {
    val file = File(filesDir, "network-ca.pem")
    if (!file.exists()) {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val certificates =
            factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().flatMap {
                it.acceptedIssuers.toList()
            }
        val text =
            certificates.joinToString("\n") {
                "-----BEGIN CERTIFICATE-----\n" +
                    Base64.encodeToString(it.encoded, Base64.NO_WRAP)
                        .chunked(64)
                        .joinToString("\n") +
                    "\n-----END CERTIFICATE-----\n"
            }
        file.writeText(text)
    }
    return file
}

fun workNotification(
    context: Context,
    id: Int,
    title: String,
    stage: String,
    processing: Boolean = false,
): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            "transfers",
            "Downloads and synchronization",
            NotificationManager.IMPORTANCE_LOW,
        )
    )
    val notification =
        NotificationCompat.Builder(context, "transfers")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(stage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    return if (Build.VERSION.SDK_INT >= 29)
        ForegroundInfo(
            id,
            notification,
            if (processing && Build.VERSION.SDK_INT >= 35)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    else ForegroundInfo(id, notification)
}

class DownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        applicationContext.graph.downloadMutex.withLock { performDownload() }

    private suspend fun performDownload(): Result =
        withContext(Dispatchers.IO) {
            val g = applicationContext.graph
            val id = inputData.getString("job") ?: return@withContext Result.failure()
            var job = g.dao.job(id) ?: return@withContext Result.success()
            if (job.state != "Queued") return@withContext Result.success()
            if (job.kind == "Video") {
                g.dao.putJob(job.copy(state = "Cancelled", stage = "Video downloads removed", error = "Only audio downloads are supported"))
                return@withContext Result.success()
            }
            setForeground(
                workNotification(applicationContext, id.hashCode(), job.title, "Resolving audio")
            )
            val stage = File(applicationContext.filesDir, "downloads/" + id).also { it.mkdirs() }
            var lastUpdate = 0L
            suspend fun update(value: DownloadJob) {
                g.db.withTransaction {
                    val current = g.dao.job(id)
                    if (current == null || current.state in listOf("Paused", "Cancelled"))
                        throw CancellationException("Download stopped")
                    job = value
                    g.dao.putJob(value)
                }
            }
            try {
                update(job.copy(state = "Resolving", stage = "Finding best audio", error = ""))
                var stream: Stream? = null
                var downloaded: File? = null
                for (attempt in 0..1) {
                    try {
                        stream =
                            g.online.resolve(
                                Source(
                                    job.provider,
                                    job.sourceId,
                                    job.title,
                                    job.uploader,
                                    job.artist,
                                    job.url,
                                    job.art,
                                ),
                                refresh = attempt > 0,
                                video = false,
                            )
                        currentCoroutineContext().ensureActive()
                        val resolved = stream
                        update(
                            job.copy(
                                title = resolved.source.title,
                                uploader = resolved.source.uploader,
                                sourceId = resolved.source.id,
                                artist = resolved.source.artist,
                                art = resolved.source.thumbnail,
                                state = "Active",
                                stage = "Downloading",
                                refreshes = attempt,
                            )
                        )
                        setForeground(
                            workNotification(
                                applicationContext,
                                id.hashCode(),
                                job.title,
                                "Downloading audio",
                            )
                        )
                        val file = File(stage, "source." + resolved.suffix)
                        val remote = resolved.url
                        val manifest =
                            resolved.delivery == "HLS" ||
                                resolved.mime.contains("mpegurl", true) ||
                                remote.substringBefore('?').endsWith(".m3u8")
                        if (manifest) {
                            val output =
                                File(
                                    stage,
                                    "source." +
                                        if (resolved.suffix in listOf("m3u8", "audio")) "m4a"
                                        else resolved.suffix,
                                )
                            val ok =
                                NativeMedia.convert(
                                    remote,
                                    output.path,
                                    false,
                                    applicationContext.certificates().path,
                                    resolved.headers.entries.joinToString("") {
                                        "${it.key}: ${it.value}\r\n"
                                    },
                                    object : NativeMedia.Progress {
                                        override fun update(done: Long, total: Long): Boolean {
                                            if (isStopped) return false
                                            if (
                                                done > 0 &&
                                                    System.currentTimeMillis() - lastUpdate > 300
                                            ) {
                                                lastUpdate = System.currentTimeMillis()
                                                runBlocking {
                                                    update(
                                                        job.copy(
                                                            bytes = done,
                                                            total = total,
                                                            progress =
                                                                if (total > 0) done * 100f / total
                                                                else -1f,
                                                        )
                                                    )
                                                }
                                            }
                                            return true
                                        }
                                    },
                                )
                            require(ok) { "Stream packaging failed" }
                            downloaded = output
                        } else {
                            transferAudio(g.online.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build(),
                                remote, resolved.headers, file, job.provider == "youtube") { done, total ->
                                if (isStopped) throw CancellationException()
                                if (System.currentTimeMillis() - lastUpdate >= 500) {
                                    lastUpdate = System.currentTimeMillis()
                                    update(job.copy(bytes = done, total = total,
                                        progress = if (total > 0) done * 100f / total else -1f))
                                }
                            }
                            downloaded = file
                        }
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (
                            attempt == 0 &&
                                job.provider != "direct" &&
                                e.message?.contains("403") == true
                        )
                            continue
                        throw e
                    }
                }
                val file = downloaded ?: error("No audio file was produced")
                val resolved = stream ?: error("Missing source metadata")
                currentCoroutineContext().ensureActive()
                update(
                    job.copy(
                        state = "Post-processing",
                        progress = -1f,
                        stage = if (job.kind == "MP3") "Converting to MP3" else "Writing metadata",
                    )
                )
                setForeground(
                    workNotification(applicationContext, id.hashCode(), job.title, job.stage, true)
                )
                val result =
                    if (job.kind == "MP3")
                        File(stage, "audio.mp3").also { target ->
                            require(
                                NativeMedia.convert(
                                    file.path,
                                    target.path,
                                    true,
                                    "",
                                    "",
                                    object : NativeMedia.Progress {
                                        override fun update(done: Long, total: Long) = !isStopped
                                    },
                                )
                            ) {
                                "MP3 conversion failed"
                            }
                        }
                    else if (job.provider != "direct" && file.extension == "webm")
                        File(stage, "audio.ogg").also { target ->
                            require(NativeMedia.convert(file.path, target.path, false, "", "",
                                object : NativeMedia.Progress { override fun update(done: Long, total: Long) = !isStopped })) {
                                "Audio packaging failed"
                            }
                        }
                    else file
                val cover = downloadCover(g.online.http, job.art, stage, job.sourceId.takeIf { job.provider == "youtube" }.orEmpty())
                val metadata =
                    buildJsonObject {
                            put("title", job.title)
                            put("artist", job.artist.ifBlank { "Unknown artist" })
                            put(
                                "comment",
                                "${job.url} · Uploader: ${job.uploader} · Original title: ${job.title}",
                            )
                        }
                        .toString()
                // original formats without writable tags retain complete attribution in the database
                val tagged =
                    if (job.provider == "direct") true else NativeMedia.edit(result.path, metadata, cover?.path ?: "")
                require(tagged || job.kind != "MP3") { "Could not embed MP3 metadata" }
                currentCoroutineContext().ensureActive()
                val extension = if (job.kind == "MP3") "mp3" else result.extension
                var indexed: Track? = null
                val output =
                    g.library.publishDownload(
                        result,
                        "${safeName(job.title)} [${safeName(job.sourceId).take(40)}] ${id.take(8)}.$extension",
                        if (job.kind == "MP3") "audio/mpeg"
                        else if (extension == "ogg") "audio/ogg" else resolved.mime.ifBlank { "audio/*" },
                        job.destination,
                        resolved.source,
                        cover,
                        { indexed = it },
                    )
                update(job.copy(output = output, stage = "Indexing audio", progress = -1f))
                g.online.attach(job, indexed)
                update(
                    job.copy(state = "Completed", stage = "Completed", progress = 100f, error = "")
                )
                stage.deleteRecursively()
                Result.success()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val current = g.dao.job(id)
                    if (current != null && current.state !in listOf("Paused", "Cancelled"))
                        g.dao.putJob(
                            current.copy(
                                state = "Interrupted",
                                stage = "Interrupted",
                                error = "Retry to continue",
                            )
                        )
                }
                throw e
            } catch (e: Exception) {
                if (isStopped) return@withContext Result.success()
                update(
                    job.copy(
                        state = if (e is AudioTransferInterruptedException) "Interrupted" else "Failed",
                        stage = if (e is AudioTransferInterruptedException) "Connection interrupted" else "Failed",
                        error =
                            (e.message ?: "Download failed")
                                .replace(Regex("https?://[^\\s]+"), "media URL")
                                .take(1000),
                    )
                )
                Result.failure()
            }
        }
}
