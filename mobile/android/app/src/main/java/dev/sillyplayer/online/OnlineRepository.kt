package dev.sillyplayer.online

import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

private val audioExtensions = setOf("mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "webm")

private fun audioSuffix(url: String) =
    Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()

fun provider(url: String): String {
    val u = Uri.parse(url.trim())
    if (u.scheme !in listOf("http", "https") || u.host.isNullOrBlank() || u.userInfo != null) return "invalid"
    return when (u.host?.lowercase()) {
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be" -> "youtube"
        "soundcloud.com",
        "www.soundcloud.com",
        "m.soundcloud.com",
        "on.soundcloud.com" -> "soundcloud"
        else -> "direct"
    }
}

internal fun selectAudio(streams: List<AudioStream>): AudioStream =
    streams.filter { it.isUrl && it.content.isNotBlank() }
        .maxWithOrNull(compareBy<AudioStream> { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .thenBy { it.averageBitrate })
        ?: error("No accessible audio stream is available; the content may require authentication")

data class SearchState(
    val query: String = "",
    val rows: List<Source> = emptyList(),
    val loading: Boolean = false,
    val message: String = "",
    val more: Boolean = false,
)

data class CollectionState(
    val url: String = "",
    val title: String = "",
    val rows: List<Source> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val loading: Boolean = false,
    val more: Boolean = false,
    val message: String = "",
)

class OnlineRepository(private val g: Graph) {
    val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    val searches =
        MutableStateFlow(
            mapOf(
                "youtube" to SearchState(),
                "soundcloud" to SearchState(),
                "direct" to SearchState(),
            )
        )
    val collection = MutableStateFlow<CollectionState?>(null)
    val jobs = g.dao.observeJobs().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val history = g.dao.observeSearches().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val resolving = MutableStateFlow(false)
    val opened = MutableStateFlow<Source?>(null)
    private val pages = mutableMapOf<String, Page?>()
    private var collectionPage: Page? = null
    private val requests = mutableMapOf<String, Job>()
    private var playbackRequest: Job? = null
    private var requestedVideo = false
    private var collectionRequest: Job? = null
    private val cache = StreamCache()
    private val transport = ExtractorTransport(http)
    private val catalogue = Mutex()

    init {
        NewPipe.init(transport)
        g.task {
            for (job in g.dao.jobs()) if (
                job.state in listOf("Active", "Resolving", "Post-processing")
            )
                g.dao.putJob(
                    job.copy(state = "Interrupted", error = "Interrupted; retry to continue")
                )
        }
    }

    private fun service(provider: String): StreamingService =
        NewPipe.getService(if (provider == "soundcloud") "SoundCloud" else "YouTube")

    private fun row(item: StreamInfoItem, provider: String) =
        Source(
            provider,
            id = Uri.parse(item.url).getQueryParameter("v") ?: item.url,
            title = item.name,
            uploader = item.uploaderName ?: "",
            url = item.url,
            thumbnail = sourceThumbnail(
                item.thumbnails.filter { it.url.isNotBlank() }
                    .minByOrNull { if (it.width in 240..640) 0 else if (it.width > 0) kotlin.math.abs(it.width - 480) else 10000 }?.url.orEmpty(),
                if (provider == "youtube") (Uri.parse(item.url).getQueryParameter("v") ?: Uri.parse(item.url).lastPathSegment).orEmpty() else "",
            ),
            duration = item.duration.toDouble(),
            playable =
                item.streamType !in listOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM),
            reason =
                if (item.streamType in listOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM))
                    "Live streams are unsupported"
                else "",
        )

    private fun set(provider: String, block: (SearchState) -> SearchState) {
        searches.update { it + (provider to block(it[provider] ?: SearchState())) }
    }

    fun search(provider: String, query: String, more: Boolean = false) {
        requests.remove(provider)?.cancel()
        set(provider) { it.copy(loading = false) }
        requests[provider] =
            g.scope.launch(Dispatchers.Main.immediate) {
                val q = query.trim()
                if (q.isEmpty()) {
                    set(provider) { SearchState() }
                    return@launch
                }
                if (q.startsWith("http://") || q.startsWith("https://")) {
                    if (dev.sillyplayer.online.provider(q) == "invalid") {
                        g.message.value = "Use an HTTP or HTTPS media link"
                        return@launch
                    }
                    if (
                        dev.sillyplayer.online.provider(q) == "youtube" &&
                            !Uri.parse(q).getQueryParameter("list").isNullOrBlank()
                    ) {
                        loadCollection(q)
                        return@launch
                    }
                    play(
                        Source(
                            dev.sillyplayer.online.provider(q),
                            url = q,
                            title = Uri.parse(q).lastPathSegment ?: "Audio",
                        )
                    )
                    return@launch
                }
                if (provider == "direct") {
                    g.message.value = "Paste an HTTP or HTTPS audio link"
                    return@launch
                }
                val previous = searches.value[provider] ?: SearchState()
                set(provider) {
                    if (more) it.copy(loading = true, message = "")
                    else SearchState(q, loading = true)
                }
                try {
                    val next = pages[provider].takeIf { more && previous.query == q }
                    val page =
                        transport.lookup {
                            val filter = if (provider == "youtube") "videos" else "tracks"
                            val extractor = service(provider).getSearchExtractor(q, listOf(filter), "")
                            if (next != null) extractor.getPage(next)
                            else {
                                extractor.fetchPage()
                                extractor.initialPage
                            }
                        }
                    ensureActive()
                    val rows =
                        page.items.filterIsInstance<StreamInfoItem>().map { row(it, provider) }
                    pages[provider] = page.nextPage
                    g.dao.putSearch(Search(provider, q))
                    set(provider) {
                        SearchState(
                            q,
                            ((if (more) previous.rows else emptyList()) + rows).take(500),
                            false,
                            if (rows.isEmpty()) "No results" else "",
                            Page.isValid(page.nextPage) &&
                                ((if (more) previous.rows.size else 0) + rows.size) < 500,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    set(provider) {
                        it.copy(loading = false, message = e.message ?: "Search failed")
                    }
                }
            }
    }

    suspend fun resolve(source: Source, refresh: Boolean = false, video: Boolean = false): Stream =
        cache.resolve(source.url, video, refresh) { extract(source, video) }

    private suspend fun extract(source: Source, video: Boolean): Stream =
        transport.lookup {
            require(provider(source.url) != "invalid") { "Use an HTTP or HTTPS link" }
            if (source.provider == "direct") {
                val request = okhttp3.Request.Builder().url(source.url).head().build()
                fun probe(request: okhttp3.Request) = transport.withResponse(request) {
                    it.code to it.header("Content-Type").orEmpty().substringBefore(';')
                }
                val head = probe(request)
                val (code, mime) = if (head.first == 405 || head.first == 501)
                    probe(request.newBuilder().get().header("Range", "bytes=0-0").build())
                else head
                require(code in 200..299) { "HTTP $code" }
                require(
                    mime.startsWith("audio/") ||
                        audioSuffix(source.url) in audioExtensions
                ) {
                    "This link does not identify a supported audio file"
                }
                return@lookup Stream(
                    source,
                    source.url,
                    mime,
                    audioSuffix(source.url).takeIf { it.length in 1..5 } ?: "audio",
                )
            }
            val extractor = service(source.provider).getStreamExtractor(source.url)
            extractor.fetchPage()
            require(
                extractor.streamType !in
                    listOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM)
            ) {
                "Live streams are unsupported"
            }
            val actual =
                source.copy(
                    id = extractor.id,
                    title = extractor.name,
                    uploader = extractor.uploaderName,
                    url = extractor.url,
                    thumbnail =
                        sourceThumbnail(extractor.thumbnails.filter { it.url.isNotBlank() }
                            .minByOrNull { kotlin.math.abs(it.width - 480) }?.url ?: source.thumbnail,
                            if (source.provider == "youtube") extractor.id else ""),
                    duration = extractor.length.toDouble(),
                )
            if (video) {
                require(source.provider == "youtube") { "Video playback is available for YouTube" }
                val selected =
                    extractor.videoStreams
                        .filter {
                            it.isUrl &&
                                !it.isVideoOnly &&
                                it.deliveryMethod ==
                                    org.schabi.newpipe.extractor.stream.DeliveryMethod
                                        .PROGRESSIVE_HTTP &&
                                it.format?.suffix == "mp4"
                        }
                        .maxByOrNull {
                            it.resolution.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0
                        }
                        ?: error(
                            "No accessible combined MP4 video stream is available. Audio may still be available."
                        )
                return@lookup Stream(
                    actual,
                    selected.content,
                    "video/mp4",
                    "mp4",
                    headers = mapOf("User-Agent" to "Mozilla/5.0"),
                    expires = streamExpiry(selected.content),
                )
            }
            val audio = selectAudio(extractor.audioStreams)
            Stream(
                actual,
                audio.content,
                audio.format?.mimeType ?: "",
                (audio.format?.suffix ?: "m4a").replace("webma", "webm"),
                audio.averageBitrate,
                mapOf("User-Agent" to "Mozilla/5.0 (Android) SillyPlayer/0.1"),
                streamExpiry(audio.content),
                delivery = audio.deliveryMethod.name,
            )
        }

    private fun streamExpiry(url: String): Long {
        val now = System.currentTimeMillis()
        val seconds = Uri.parse(url).getQueryParameter("expire")?.toLongOrNull() ?: return now + 30_000
        return minOf(now + 120_000, seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 - 60_000)
    }

    fun play(source: Source, video: Boolean = false, refresh: Boolean = false) {
        playbackRequest?.cancel()
        opened.value = source
        requestedVideo = video
        g.engine.value?.leaveBrowse()
        resolving.value = true
        playbackRequest =
            g.scope.launch(Dispatchers.Main.immediate) {
                try {
                    val directSuffix = audioSuffix(source.url)
                    val stream = if (source.provider == "direct" && provider(source.url) == "direct" && directSuffix in audioExtensions)
                        Stream(source, source.url, suffix = directSuffix)
                    else resolve(source, refresh, video)
                    ensureActive()
                    opened.value = stream.source
                    val engine = withTimeoutOrNull(15_000) { g.engine.filterNotNull().first().also { it.ready.await() } }
                        ?: error("Playback service is unavailable")
                    engine.startOnline(
                        Track(
                            id = "online:" + source.provider + ":" + stream.source.id.ifBlank { stream.source.url },
                            uri = stream.url,
                            title = stream.source.title,
                            artist = stream.source.artist,
                            art = stream.source.thumbnail,
                            duration = stream.source.duration,
                            sourceUrl = stream.source.url,
                            uploader = stream.source.uploader,
                            sourceProvider = if (video) "youtube-video" else source.provider,
                        ),
                        headers = stream.headers,
                        mime =
                            if (stream.delivery == "HLS")
                                androidx.media3.common.MimeTypes.APPLICATION_M3U8
                            else stream.mime.takeIf { it.isNotBlank() },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    g.message.value = e.message ?: "Could not load audio"
                } finally {
                    if (isActive) resolving.value = false
                }
            }
    }

    fun retryPlayback() {
        opened.value?.let { play(it, video = requestedVideo, refresh = true) }
    }

    fun closePlayer() {
        opened.value = null
        cancelPlayback()
        g.engine.value?.leaveBrowse()
    }

    fun cancelPlayback() {
        playbackRequest?.cancel()
        playbackRequest = null
        resolving.value = false
    }

    fun loadCollection(url: String, more: Boolean = false) {
        collectionRequest?.cancel()
        collectionRequest =
            g.scope.launch {
                val old = collection.value ?: CollectionState()
                collection.value =
                    if (more) old.copy(loading = true, message = "")
                    else CollectionState(url = url, loading = true)
                try {
                    require(provider(url) == "youtube") { "Use a YouTube playlist or mix link" }
                    val svc = service("youtube")
                    val info = if (!more) transport.lookup { PlaylistInfo.getInfo(svc, url) } else null
                    val page =
                        if (more) transport.lookup { PlaylistInfo.getMoreItems(svc, url, collectionPage) } else null
                    val rows = (page?.items ?: info!!.relatedItems).map { row(it, "youtube") }
                    ensureActive()
                    val start = if (more) old.rows.size else 0
                    val combined = (if (more) old.rows else emptyList()) + rows
                    collectionPage = page?.nextPage ?: info?.nextPage
                    collection.value =
                        CollectionState(
                            url,
                            info?.name ?: old.title,
                            combined,
                            if (more) old.selected
                            else rows.indices.filter { rows[it].playable }.toSet(),
                            false,
                            Page.isValid(collectionPage),
                            if (Uri.parse(url).getQueryParameter("list")?.startsWith("RD") == true)
                                "Mix snapshot: YouTube may change its contents"
                            else "",
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    collection.value =
                        collection.value?.copy(
                            loading = false,
                            message = e.message ?: "Playlist lookup failed",
                        )
                }
            }
    }

    fun cancelCollectionLoading() {
        collectionRequest?.cancel()
        collection.update {
            it?.copy(loading = false, message = "Loading canceled; retry to continue")
        }
    }

    fun dismissCollection() {
        collectionRequest?.cancel()
        collection.value = null
        collectionPage = null
    }

    fun select(index: Int) {
        collection.update {
            it?.copy(
                selected = if (index in it.selected) it.selected - index else it.selected + index
            )
        }
    }

    fun selectAll() {
        collection.update { c ->
            c?.let {
                val all = it.rows.indices.filter { i -> it.rows[i].playable }.toSet()
                it.copy(selected = if (it.selected == all) emptySet() else all)
            }
        }
    }

    suspend fun enqueue(
        source: Source,
        kind: String = "Original",
        again: Boolean = false,
        scheduleNow: Boolean = true,
    ): String {
        require(kind in listOf("Original", "MP3")) { "Only audio downloads are supported" }
        require(source.provider != "direct" || kind == "Original") {
            "Direct links preserve original audio"
        }
        require(g.prefs.state.value.musicDir.isNotBlank()) {
            "Choose a download folder in Settings"
        }
        val existing =
            g.dao.jobs().firstOrNull {
                it.url == source.url &&
                    it.kind == kind &&
                    it.state !in listOf("Failed", "Cancelled", "Interrupted") &&
                    (it.state != "Completed" ||
                        it.output.isNotBlank() &&
                            runCatching { g.input(it.output).use { f -> f.read() } }.isSuccess)
            }
        if (existing != null && (!again || existing.state != "Completed")) return existing.id
        val job =
            DownloadJob(
                provider = source.provider,
                sourceId = source.id,
                url = source.url,
                title = source.title,
                uploader = source.uploader,
                artist = source.artist,
                art = source.thumbnail,
                kind = kind,
                destination = g.prefs.state.value.musicDir,
            )
        g.dao.putJob(job)
        if (scheduleNow) schedule(job.id)
        return job.id
    }

    suspend fun enqueueCollection(kind: String, name: String?, again: Boolean = false) {
        val c = collection.value ?: return
        val ids = c.selected.sorted()
        require(ids.isNotEmpty()) { "Select at least one song" }
        var playlist: String? = null
        val queued = mutableListOf<String>()
        g.db.withTransaction {
            playlist = if (name != null && kind != "Video") g.library.collection(name) else null
            for ((position, index) in ids.withIndex()) {
                val job = enqueue(c.rows[index], kind, again, false)
                queued += job
                playlist?.let {
                    g.dao.putDownloadMember(
                        DownloadMember(collection = it, job = job, position = position)
                    )
                }
            }
        }
        for (id in queued.distinct()) {
            val job = g.dao.job(id) ?: continue
            if (job.state == "Completed") attach(job) else if (job.state == "Queued") schedule(id)
        }
        collection.value =
            c.copy(
                selected = emptySet(),
                message =
                    if (playlist != null) "Created $name; songs are added as downloads finish"
                    else "Selected downloads queued",
            )
    }

    private fun schedule(id: String) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("job" to id))
                .addTag("download:" + id)
                .build()
        WorkManager.getInstance(g.context)
            .beginUniqueWork("download:" + id, ExistingWorkPolicy.KEEP, request)
            .enqueue()
    }

    suspend fun repairDownloadedSongs() = g.downloadMutex.withLock {
        var repaired = 0
        val failures = mutableListOf<String>()
        for (job in g.dao.jobs().filter { it.state == "Completed" && it.kind != "Video" && it.output.isNotBlank() }) {
            try {
                val existing = g.dao.byUri(job.output) ?: continue
                if (existing.deleted) continue
                val source = Source(job.provider, job.sourceId, job.title, job.uploader, job.artist, job.url, job.art)
                var track = g.library.index(job.output, source, force = true)
                if (track.art.isBlank() && job.art.isNotBlank()) {
                    val directory = File(g.context.cacheDir, "cover-repair").also { it.mkdirs() }
                    try {
                        downloadCover(http, job.art, directory, job.sourceId.takeIf { job.provider == "youtube" }.orEmpty())?.let { cover ->
                            val target = File(g.context.filesDir, "art/downloads/${track.id}.jpg")
                            target.parentFile?.mkdirs(); cover.copyTo(target, overwrite = true)
                            track = track.copy(art = target.toURI().toString())
                            g.library.saveTrack(track)
                        }
                    } finally { directory.deleteRecursively() }
                }
                repaired++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += "${job.title}: ${e.message}" }
        }
        g.message.value = "Repaired $repaired downloaded songs" + if (failures.isEmpty()) "" else "\n" + failures.joinToString("\n")
    }

    suspend fun attach(job: DownloadJob, indexed: Track? = null) {
        if (job.output.isBlank()) return
        val source =
            Source(
                job.provider,
                job.sourceId,
                job.title,
                job.uploader,
                job.artist,
                job.url,
                job.art,
            )
        val track = indexed ?: g.library.index(job.output, source)
        g.db.withTransaction {
            val changed = mutableSetOf<String>()
            for (p in g.dao.attachments(job.id)) {
                if (g.dao.collection(p.collection) != null) {
                    val member = Member(p.id, p.collection, track.id, p.position)
                    g.dao.putMember(member)
                    g.library.journal("members", member.id, JsonCodec.encodeToJsonElement(member))
                    changed += p.collection
                }
                g.dao.deleteAttachment(p.id)
            }
            for (collection in changed) g.library.order(collection)
        }
    }

    suspend fun stop(id: String, pause: Boolean) {
        val job = g.dao.job(id) ?: return
        g.dao.putJob(job.copy(state = if (pause) "Paused" else "Cancelled"))
        WorkManager.getInstance(g.context).cancelAllWorkByTag("download:" + id)
        if (!pause) File(g.context.filesDir, "downloads/" + id).deleteRecursively()
    }

    suspend fun retry(id: String) {
        val job = g.dao.job(id) ?: return
        g.dao.putJob(job.copy(state = "Queued", error = ""))
        schedule(id)
    }

    suspend fun remove(id: String) {
        val job = g.dao.job(id) ?: return
        require(job.state !in listOf("Queued", "Active", "Resolving", "Post-processing")) {
            "Cancel this job first"
        }
        for (p in g.dao.attachments(id)) g.dao.deleteAttachment(p.id)
        File(g.context.filesDir, "downloads/" + id).deleteRecursively()
        g.dao.deleteJob(id)
    }

    suspend fun clearHistory(failed: Boolean) {
        for (job in g.dao.jobs()) if (
            job.state in
                if (failed) listOf("Failed", "Cancelled", "Interrupted") else listOf("Completed")
        )
            remove(job.id)
    }

    suspend fun lookupMetadata(title: String, artist: String): List<Pair<String, String>> =
        catalogue.withLock {
            delay(1000)
            val query =
                "recording:\"${title.replace("\"","")}\" AND artist:\"${artist.replace("\"","")}\""
            val url =
                "https://musicbrainz.org/ws/2/recording/"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("fmt", "json")
                    .addQueryParameter("limit", "8")
                    .build()
            http
                .newCall(
                    okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "SillyPlayer/0.1 (local-first music player)")
                        .build()
                )
                .execute()
                .use { r ->
                    require(r.isSuccessful) { "Metadata lookup HTTP ${r.code}" }
                    val json = JsonCodec.parseToJsonElement(r.body!!.string()).jsonObject
                    json["recordings"]?.jsonArray?.map { v ->
                        val o = v.jsonObject
                        val credits =
                            o["artist-credit"]?.jsonArray?.joinToString("") {
                                it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                            } ?: ""
                        (o["title"]?.jsonPrimitive?.content ?: "") to credits
                    } ?: emptyList()
                }
        }
}
