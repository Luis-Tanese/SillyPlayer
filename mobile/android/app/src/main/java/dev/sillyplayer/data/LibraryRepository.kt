package dev.sillyplayer.data

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.sillyplayer.Graph
import dev.sillyplayer.media.NativeMedia
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.json.JSONObject

fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun Graph.input(value: String): InputStream =
    if (value.startsWith("content:"))
        context.contentResolver.openInputStream(Uri.parse(value)) ?: error("Cannot read file")
    else FileInputStream(if (value.startsWith("file:")) Uri.parse(value).path!! else value)

fun Graph.output(value: String): OutputStream =
    if (value.startsWith("content:"))
        context.contentResolver.openOutputStream(Uri.parse(value), "rwt")
            ?: error("Folder does not support safe replacement")
    else FileOutputStream(if (value.startsWith("file:")) Uri.parse(value).path!! else value)

fun safeName(value: String): String =
    value.replace(Regex("[\\x00-\\x1f/\\\\:*?\"<>|]"), "_").take(150).ifBlank { "Audio" }

class LibraryRepository(private val g: Graph) {
    val tracks = g.dao.observeTracks().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val collections =
        g.dao.observeCollections().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val members = g.dao.observeMembers().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val likes =
        g.dao
            .observeLikes()
            .map { it.map { e -> e.track }.toSet() }
            .stateIn(g.scope, SharingStarted.WhileSubscribed(5000), emptySet())
    val roots = g.dao.observeRoots().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val trash = g.dao.observeTrash().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val status = MutableStateFlow("")
    private val scanning = AtomicBoolean(false)
    private val audio =
        setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "webm", "mp4")

    private var observation: android.database.ContentObserver? = null
    private var scanRequest: Job? = null
    val ready = CompletableDeferred<Unit>()
    @Volatile private var lastScan = 0L
    private val scanPending = AtomicBoolean(false)
    val recovery = LibraryRecovery(g)

    fun observeRecovery() = recovery.observe()

    init {
        g.task {
            try {
                g.fileGate.withLock {
                    recoverWrites()
                    recoverTrash()
                }
            } finally {
                ready.complete(Unit)
            }
            reconcile()
        }
        g.scope.launch {
            roots.collect { registered ->
                observation?.let { g.context.contentResolver.unregisterContentObserver(it) }
                val observer =
                    object :
                        android.database.ContentObserver(
                            android.os.Handler(android.os.Looper.getMainLooper())
                        ) {
                        override fun onChange(selfChange: Boolean) {
                            scanPending.set(true)
                            if (!g.foreground.value) return
                            scanRequest?.cancel()
                            scanRequest =
                                g.scope.launch {
                                    delay(700)
                                    g.task { reconcile() }
                                }
                        }
                    }
                observation = observer
                for (root in registered) runCatching {
                    g.context.contentResolver.registerContentObserver(
                        Uri.parse(root.uri),
                        true,
                        observer,
                    )
                }
            }
        }
        g.scope.launch {
            g.foreground.collectLatest { visible ->
                if (!visible) return@collectLatest
                if (scanPending.get() || android.os.SystemClock.elapsedRealtime() - lastScan >= 120000)
                    g.task { reconcile() }
                while (isActive) {
                    delay(120000)
                    if (roots.value.isNotEmpty()) g.task { reconcile() }
                }
            }
        }
    }

    fun grant(
        uri: Uri,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) {
        runCatching { g.context.contentResolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching {
                g.context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
    }

    suspend fun register(uri: Uri) {
        grant(uri)
        val root = DocumentFile.fromTreeUri(g.context, uri) ?: error("Folder is unavailable")
        val existing = g.dao.roots().firstOrNull { it.uri == uri.toString() }
        g.dao.putRoot(
            Root(
                id = existing?.id ?: uuid(),
                uri = uri.toString(),
                name = root.name ?: "Music folder",
            )
        )
        reconcile()
    }

    suspend fun removeRoot(id: String) {
        g.dao.deleteRoot(id)
    }

    private fun document(uri: String): DocumentFile? {
        val parsed = Uri.parse(uri)
        return if (parsed.scheme == "file") DocumentFile.fromFile(File(parsed.path ?: return null))
        else if (parsed.scheme == "content") DocumentFile.fromSingleUri(g.context, parsed)
        else null
    }

    private fun documentKey(value: String): String =
        runCatching {
                val u = Uri.parse(value)
                u.authority + ":" + DocumentsContract.getDocumentId(u)
            }
            .getOrDefault(value)

    val isIndexing: Boolean
        get() = scanning.get()

    suspend fun reconcile(force: Boolean = false) {
        if (g.resetting.value) return
        if (!scanning.compareAndSet(false, true)) {
            scanPending.set(true)
            return
        }
        scanPending.set(false)
        val errors = mutableListOf<String>()
        try {
            val known = HashMap<String, Track>()
            val knownHashes = HashMap<String, MutableList<Track>>()
            for (track in g.dao.recoveryTracks()) {
                known[documentKey(track.uri)] = track
                if (!track.deleted && track.mergedInto.isBlank() && track.hash.isNotBlank())
                    knownHashes.getOrPut(track.hash) { mutableListOf() }.add(track)
            }
            val knownTrash = g.dao.trash().mapTo(HashSet()) { it.track }
            for (root in g.dao.roots()) {
                val folder = DocumentFile.fromTreeUri(g.context, Uri.parse(root.uri))
                suspend fun unavailable() {
                    g.dao.putRoot(root.copy(available = false))
                    for (t in g.dao.tracks().filter { it.root == root.id }) g.dao.putTrack(
                        t.copy(available = false)
                    )
                }
                if (folder == null || !folder.canRead()) {
                    unavailable()
                    errors += "${root.name}: storage unavailable"
                    continue
                }
                val seen = mutableSetOf<String>()
                val visited = mutableSetOf<String>()
                var count = 0
                suspend fun walk(uri: Uri, depth: Int = 0) {
                    require(depth < 64 && visited.add(documentKey(uri.toString()))) {
                        "Folder cycle or excessive nesting"
                    }
                    val children =
                        DocumentsContract.buildChildDocumentsUriUsingTree(
                            uri,
                            DocumentsContract.getDocumentId(uri),
                        )
                    val projection =
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                        )
                    val cursor =
                        g.context.contentResolver.query(children, projection, null, null, null)
                            ?: error("Folder provider did not return its contents")
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val name = c.getString(1) ?: ""
                            if (name.startsWith(".sillyplayer")) continue
                            val child =
                                DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0))
                            val mime = c.getString(2) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR)
                                walk(child, depth + 1)
                            else if (
                                name.substringAfterLast('.').lowercase() in audio ||
                                    mime.startsWith("audio/")
                            ) {
                                val key = documentKey(child.toString())
                                seen += key
                                try {
                                    g.fileGate.withLock {
                                        val indexed = indexUnlocked(
                                            child.toString(),
                                            force = force,
                                            rootId = root.id,
                                            knownTrack = known[key],
                                            knownHashes = knownHashes,
                                            knownTrash = knownTrash,
                                        )
                                        known[key] = indexed
                                        knownHashes[indexed.hash]?.removeAll { it.id == indexed.id }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errors += "$name: ${e.message}"
                                }
                                status.value = "Indexed ${++count} files"
                            }
                        }
                    }
                    require(DocumentFile.fromSingleUri(g.context, uri)?.canRead() == true) {
                        "Storage became unavailable during scan"
                    }
                }
                try {
                    walk(folder.uri)
                    g.dao.putRoot(root.copy(available = true))
                    val removed = g.dao.tracks().filter { it.root == root.id && documentKey(it.uri) !in seen }
                    if (removed.isNotEmpty()) {
                        g.db.withTransaction {
                            for (t in removed) {
                                g.dao.putTrack(t.removedFromLibrary())
                                journal("tracks", t.id, null)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${root.name}: ${e.message}"
                    unavailable()
                }
            }
        } finally {
            status.value =
                if (errors.isEmpty()) "Library up to date" else errors.take(8).joinToString("\n")
            lastScan = android.os.SystemClock.elapsedRealtime()
            scanning.set(false)
        }
    }

    suspend fun index(uri: String, source: Source? = null, force: Boolean = false): Track =
        g.fileGate.withLock {
            val known =
                g.dao.byUri(uri)
                    ?: g.dao.allTracks().firstOrNull { documentKey(it.uri) == documentKey(uri) }
            indexUnlocked(uri, source, force, knownTrack = known)
        }

    private fun persistentArt(bytes: ByteArray): String {
        val hash =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val target = File(g.context.filesDir, "art/indexed/$hash.img").also { it.parentFile?.mkdirs() }
        if (!target.isFile) target.writeBytes(bytes)
        return target.toURI().toString()
    }

    private fun existingArtFile(uri: String): File? {
        if (!uri.startsWith("file:")) return null
        val path = Uri.parse(uri).path ?: return null
        return File(path).takeIf { it.isFile }
    }

    private fun isCacheArt(uri: String): Boolean {
        if (!uri.startsWith("file:")) return false
        val path = Uri.parse(uri).path ?: return false
        val cacheRoot = g.context.cacheDir.path
        return path.startsWith("$cacheRoot/") || path == cacheRoot
    }

    private suspend fun indexUnlocked(
        uri: String,
        source: Source? = null,
        force: Boolean = false,
        rootId: String = "",
        knownTrack: Track? = null,
        knownHashes: Map<String, List<Track>>? = null,
        knownTrash: Set<String>? = null,
    ): Track {
        check(!g.resetting.value) { "Library reset is in progress" }
        // A folder scan already has the complete URI/hash snapshot, including removal tombstones
        var old = knownTrack ?: if (knownHashes == null) g.dao.byUri(uri) else null
        if (old?.isRemovalTombstone() == true) return old
        var name = Uri.parse(uri).lastPathSegment ?: "Audio"
        var size = 0L
        var modified = 0L
        if (uri.startsWith("content:")) {
            g.context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }
                        ?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 }
                        ?.let { modified = c.getLong(it) }
                }
            }
        } else {
            val f = File(Uri.parse(uri).path ?: uri)
            name = f.name
            size = f.length()
            modified = f.lastModified()
        }
        val oldArt = old?.art.orEmpty()
        val oldArtMissing = oldArt.startsWith("file:") && existingArtFile(oldArt) == null
        val oldArtInCache = isCacheArt(oldArt)
        if (
            old != null &&
                !force &&
                modified > 0 &&
                old.modified == modified &&
                old.size == size &&
                !old.deleted &&
                old.available &&
                !oldArtMissing
        ) {
            // migrate legacy cacheDir covers into persistent filesDir so Android/OEM cleaners don't wipe them
            val migratedArt =
                if (oldArtInCache) {
                    val cachedFile = existingArtFile(oldArt)
                    if (cachedFile != null) runCatching { persistentArt(cachedFile.readBytes()) }.getOrDefault(oldArt)
                    else oldArt
                } else oldArt
            val located = old.copy(uri = uri, root = rootId.ifBlank { old.root }, art = migratedArt)
            if (located != old) g.dao.putTrack(located)
            return located
        }
        var tags = JSONObject()
        var art = if (oldArtMissing) "" else oldArt
        try {
            g.context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { fd ->
                tags = JSONObject(NativeMedia.tags(fd.fd, name))
                val bytes = NativeMedia.artwork(fd.fd, name)
                if (bytes != null && bytes.size <= 8 * 1024 * 1024) {
                    art = persistentArt(bytes)
                }
            }
        } catch (_: Exception) {}
        if (tags.optString("title").isBlank() || art.isEmpty())
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(g.context, Uri.parse(uri))
                    if (tags.optString("title").isBlank()) {
                        for ((key, type) in
                            listOf(
                                "title" to MediaMetadataRetriever.METADATA_KEY_TITLE,
                                "artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
                                "album" to MediaMetadataRetriever.METADATA_KEY_ALBUM,
                                "albumArtist" to MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                                "durationMs" to MediaMetadataRetriever.METADATA_KEY_DURATION,
                            )) {
                            retriever.extractMetadata(type)?.let { tags.put(key, it) }
                        }
                    }
                    if (art.isEmpty())
                        retriever.embeddedPicture
                            ?.takeIf { it.size <= 8 * 1024 * 1024 }
                            ?.let { bytes -> art = persistentArt(bytes) }
                } finally {
                    retriever.release()
                }
            }
        val hash = g.input(uri).use { it.sha256() }
        if (old == null) {
            val matching = if (knownHashes != null)
                knownHashes[hash]?.firstOrNull {
                    it.id !in (knownTrash ?: emptySet()) &&
                        (!it.available || document(it.uri)?.exists() == false)
                }
            else g.dao.unavailableByHash(hash) ?: g.dao.byHash(hash)
            if (matching != null && (!matching.available || document(matching.uri)?.exists() == false) &&
                matching.mergedInto.isEmpty() &&
                (knownTrash?.contains(matching.id) ?: g.dao.trash().any { it.track == matching.id }).not())
                old = matching
        }
        val value =
            (old ?: Track()).copy(
                uri = uri,
                root = rootId.ifBlank { old?.root ?: "" },
                title =
                    tags.optString("title").ifBlank {
                        source?.title ?: old?.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
                    },
                artist = tags.optString("artist").ifBlank { source?.artist?.takeIf { it.isNotBlank() } ?: old?.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist" },
                album = tags.optString("album"),
                albumArtist = tags.optString("albumArtist"),
                genre = tags.optString("genre"),
                year = tags.optInt("year"),
                number = tags.optInt("number"),
                duration = tags.optDouble("duration", tags.optDouble("durationMs", 0.0) / 1000),
                art = art,
                available = true,
                deleted = false,
                modified = modified,
                size = size,
                hash = hash,
                fileName = name,
                sourceUrl = source?.url ?: old?.sourceUrl ?: "",
                uploader = source?.uploader ?: old?.uploader ?: "",
                videoTitle = source?.title ?: old?.videoTitle ?: "",
                sourceProvider = source?.provider ?: old?.sourceProvider ?: "unknown",
                sourceItemId = source?.id ?: old?.sourceItemId ?: "",
                managed = source != null || old?.managed == true,
            )
        val recovered = recoverIndexedMetadata(value, old)
        saveTrack(recovered)
        return recovered
    }

    suspend fun trackWire(track: Track, rehashArt: Boolean = true): JsonObject {
        val artHash =
            if (!rehashArt && track.artHash.isNotBlank()) track.artHash
            else if (track.art.isNotBlank())
                runCatching { g.input(track.art).use { it.sha256() } }.getOrDefault(track.artHash)
            else track.artHash
        val map = JsonCodec.encodeToJsonElement(track).jsonObject.toMutableMap()
        for (key in
            listOf(
                "uri",
                "art",
                "available",
                "deleted",
                "managed",
                "modified",
                "size",
                "hash",
                "mergedInto",
                "root",
            )) map.remove(key)
        map["audioHash"] = JsonPrimitive(track.hash)
        map["audioSize"] = JsonPrimitive(track.size)
        map["artHash"] = JsonPrimitive(artHash)
        map["fileName"] =
            JsonPrimitive(
                track.fileName.ifBlank {
                    document(track.uri)?.name?.takeIf {
                        it.substringAfterLast('.', "").isNotBlank()
                    } ?: safeName(track.title) + ".mp3"
                }
            )
        return JsonObject(map)
    }

    suspend fun journal(entity: String, key: String, payload: JsonElement?) {
        for (peer in g.dao.peers().map { it.id }.ifEmpty { listOf("") }) {
            val pending = g.dao.pending(peer, entity, key)
            val base = pending?.base ?: g.dao.replica(peer, entity, key)?.revision ?: 0
            g.dao.deletePending(peer, entity, key)
            g.dao.putOutbox(
                Outbox(
                    peer = peer,
                    entity = entity,
                    key = key,
                    base = base,
                    payload =
                        (if (entity == "members" && payload is JsonObject)
                                JsonObject(payload.filterKeys { it != "position" })
                            else payload)
                            ?.toString(),
                )
            )
        }
    }

    suspend fun order(collection: String) {
        val payload =
            if (g.dao.collection(collection) == null) null
            else
                buildJsonObject {
                    put("id", collection)
                    put(
                        "entries",
                        JsonArray(g.dao.members(collection).map { JsonPrimitive(it.id) }),
                    )
                }
        journal("orders", collection, payload)
    }

    suspend fun saveTrack(value: Track, rehashArt: Boolean = true) {
        val payload = trackWire(value, rehashArt)
        val normalized =
            value.copy(artHash = payload["artHash"]?.jsonPrimitive?.content ?: value.artHash)
        g.db.withTransaction {
            g.dao.putTrack(normalized)
            journal("tracks", value.id, if (normalized.deleted) null else payload)
        }
    }

    suspend fun modifiers(
        id: String,
        speed: Float? = null,
        nightcore: Boolean? = null,
        volume: Float? = null,
    ) {
        val t = g.dao.track(id) ?: return
        require(speed == null || speed.isFinite() && speed in .5f..2f)
        require(volume == null || volume.isFinite() && volume in 0f..300f)
        val changed =
            t.copy(
                speed = speed ?: t.speed,
                nightcore = nightcore ?: t.nightcore,
                volumePercent = volume ?: t.volumePercent,
            )
        if (changed == t) return
        saveTrack(changed, rehashArt = false)
        withContext(Dispatchers.Main) { g.engine.value?.updateTrack(changed) }
    }

    suspend fun collection(
        name: String,
        kind: String = "playlist",
        id: String = uuid(),
        art: String = "",
    ): String {
        require(name.isNotBlank())
        val hash =
            if (art.isNotBlank()) g.input(art).use { it.sha256() }
            else g.dao.collection(id)?.artHash ?: ""
        val c = Collection(id, name.trim(), kind, art, hash)
        g.db.withTransaction {
            g.dao.putCollection(c)
            journal(
                "collections",
                id,
                buildJsonObject {
                    put("id", id)
                    put("name", c.name)
                    put("kind", kind)
                    put("artHash", hash)
                },
            )
        }
        order(id)
        return id
    }

    suspend fun deleteCollection(id: String) {
        g.db.withTransaction {
            for (m in g.dao.members(id)) {
                g.dao.deleteMember(m.id)
                journal("members", m.id, null)
            }
            g.dao.deleteAttachments(id)
            g.dao.deleteCollection(id)
            journal("collections", id, null)
            order(id)
        }
    }

    suspend fun add(
        collection: String,
        ids: List<String>,
        allowDuplicates: Boolean = false,
    ): Boolean {
        return g.db.withTransaction {
            require(g.dao.collection(collection)?.kind in listOf("playlist", "custom")) { "This collection cannot be edited" }
            val tracks = if (allowDuplicates) ids else ids.distinct()
            require(tracks.all { g.dao.track(it)?.let { track -> !track.deleted } == true }) { "A selected song is no longer available" }
            val existing = g.dao.members(collection)
            val existingTracks = existing.asSequence().map { it.track }.toHashSet()
            if (!allowDuplicates && tracks.any { it in existingTracks }) return@withTransaction false
            var pos = (existing.maxOfOrNull { it.position } ?: -1) + 1
            for (id in tracks) {
                val m = Member(collection = collection, track = id, position = pos++)
                g.dao.putMember(m)
                journal("members", m.id, JsonCodec.encodeToJsonElement(m))
            }
            order(collection)
            true
        }
    }

    suspend fun removeMember(id: String) {
        g.db.withTransaction {
            val member = g.dao.member(id)
            g.dao.deleteMember(id)
            journal("members", id, null)
            member?.let { order(it.collection) }
        }
    }

    suspend fun reorder(values: List<Member>) {
        g.db.withTransaction {
            values.forEachIndexed { index, m ->
                val value = m.copy(position = index)
                g.dao.putMember(value)
            }
            for (collection in values.map { it.collection }.distinct()) order(collection)
        }
    }

    suspend fun like(id: String) {
        g.db.withTransaction {
            if (g.dao.isLiked(id)) {
                g.dao.deleteLike(id)
                journal("likes", id, null)
            } else {
                g.dao.putLike(Like(id))
                journal("likes", id, buildJsonObject { put("track", id) })
            }
        }
    }

    fun requireMusicFolder(folder: String = g.prefs.state.value.musicDir): DocumentFile {
        require(folder.isNotBlank()) { "Choose an audio folder in Settings first" }
        val uri = Uri.parse(folder)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        require(
            !Regex("(^|:)Android/(data|obb)(/|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(documentId)
        ) {
            "Choose a public folder such as Music, outside Android/data"
        }
        require(
            g.context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        ) {
            "Choose the audio folder again and allow folder access"
        }
        val root =
            DocumentFile.fromTreeUri(g.context, uri)
                ?: error("Audio folder is unavailable; choose it again in Settings")
        require(root.isDirectory && root.canRead() && root.canWrite()) {
            "Audio folder access was lost; choose it again in Settings"
        }
        return root
    }

    suspend fun chooseMusicFolder(uri: Uri) {
        grant(uri)
        requireMusicFolder(uri.toString())
        recovery.prepareFolder(uri.toString())
        g.prefs.update { it.copy(musicDir = uri.toString()) }
        register(uri)
    }

    fun appStoredAudio(track: Track): File? =
        runCatching {
                if (!track.uri.startsWith("file:")) return null
                val file = File(java.net.URI(track.uri)).canonicalFile
                val root = File(g.context.filesDir, "music/Synced").canonicalFile
                file.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
            }
            .getOrNull()

    val copyingMusic = MutableStateFlow(false)
    val copyMusicStatus = MutableStateFlow("")
    val clearingFolder = MutableStateFlow(false)
    val clearFolderStatus = MutableStateFlow("")

    // permanently deletes all audio files and SillyPlayer Trash inside the selected folder without journaling deletions to desktop
    suspend fun clearMusicFolder() {
        check(clearingFolder.compareAndSet(false, true)) { "Folder clearing is already running" }
        clearFolderStatus.value = "Preparing to clear audio folder…"
        try {
            g.downloadMutex.withLock {
                g.syncGate.withLock {
                    g.fileGate.withLock {
                        check(!g.resetting.value) { "Library reset is in progress" }
                        check(!g.sync.state.value.busy && !g.backup.state.value.busy) {
                            "Finish or cancel sync and backup before clearing the audio folder"
                        }
                        val folder = requireMusicFolder()
                        val folderUri = g.prefs.state.value.musicDir
                        val folderName = folder.name ?: "audio folder"
                        val folderRootId = g.dao.roots().firstOrNull { it.uri == folderUri }?.id.orEmpty()
                        val deletedKeys = HashSet<String>()
                        val visited = HashSet<String>()
                        val errors = mutableListOf<String>()
                        var deletedFiles = 0

                        fun deleteDocumentUri(uri: Uri): Boolean =
                            runCatching { DocumentsContract.deleteDocument(g.context.contentResolver, uri) }
                                .getOrDefault(false) ||
                                runCatching { DocumentFile.fromSingleUri(g.context, uri)?.delete() == true }
                                    .getOrDefault(false)

                        suspend fun walkAndClear(uri: Uri, depth: Int = 0, insideTrash: Boolean = false): Boolean {
                            require(depth < 64 && visited.add(documentKey(uri.toString()))) {
                                "Folder cycle or excessive nesting"
                            }
                            val children =
                                DocumentsContract.buildChildDocumentsUriUsingTree(
                                    uri,
                                    DocumentsContract.getDocumentId(uri),
                                )
                            val projection =
                                arrayOf(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                                )
                            val cursor =
                                g.context.contentResolver.query(children, projection, null, null, null)
                                    ?: return false
                            var allChildrenRemoved = true
                            cursor.use { c ->
                                while (c.moveToNext()) {
                                    currentCoroutineContext().ensureActive()
                                    val docId = c.getString(0) ?: continue
                                    val name = c.getString(1) ?: ""
                                    val mime = c.getString(2) ?: ""
                                    // preserve the folder's saved database files, they will be updated cleanly below
                                    if (!insideTrash && name.startsWith(".sillyplayer-library-")) {
                                        allChildrenRemoved = false
                                        continue
                                    }
                                    val child = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                                    val key = documentKey(child.toString())
                                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                        val isTrashDir = insideTrash || name == ".sillyplayer-trash"
                                        val emptied = walkAndClear(child, depth + 1, isTrashDir)
                                        if (emptied && (isTrashDir || depth > 0 || name != ".sillyplayer-trash")) {
                                            if (!deleteDocumentUri(child)) allChildrenRemoved = false
                                        } else {
                                            allChildrenRemoved = false
                                        }
                                    } else if (
                                        insideTrash ||
                                            name.substringAfterLast('.', "").lowercase() in audio ||
                                            mime.startsWith("audio/")
                                    ) {
                                        if (deleteDocumentUri(child)) {
                                            deletedKeys += key
                                            deletedFiles++
                                            if (deletedFiles % 5 == 0 || deletedFiles == 1) {
                                                clearFolderStatus.value = "Deleting songs ($deletedFiles)…"
                                            }
                                        } else {
                                            allChildrenRemoved = false
                                            if (errors.size < 5) errors += "$name: could not delete"
                                        }
                                    } else {
                                        allChildrenRemoved = false
                                    }
                                }
                            }
                            return allChildrenRemoved
                        }

                        walkAndClear(folder.uri)

                        val affectedTrackIds = HashSet<String>()
                        for (track in g.dao.recoveryTracks()) {
                            if (
                                (folderRootId.isNotBlank() && track.root == folderRootId) ||
                                    documentKey(track.uri) in deletedKeys
                            ) {
                                affectedTrackIds += track.id
                            }
                        }
                        for (entry in g.dao.trash()) {
                            if (
                                documentKey(entry.stored) in deletedKeys ||
                                    documentKey(entry.original) in deletedKeys ||
                                    entry.track in affectedTrackIds
                            ) {
                                affectedTrackIds += entry.track
                            }
                        }

                        if (affectedTrackIds.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                g.engine.value?.removeTracks(affectedTrackIds)
                            }
                            g.db.withTransaction {
                                for (chunk in affectedTrackIds.chunked(400)) {
                                    g.dao.deleteMembersByTrackIds(chunk)
                                    g.dao.deleteLikesByTrackIds(chunk)
                                    g.dao.deleteTrashByTrackIds(chunk)
                                    g.dao.deleteOutboxForKeys(chunk)
                                    g.dao.deleteReplicasForKeys(chunk)
                                    g.dao.deleteConflictsForKeys(chunk)
                                    g.dao.deleteTracksByIds(chunk)
                                }
                                g.dao.resetPeerCursors()
                            }
                        }
                        runCatching { recovery.saveNow(allowEmpty = true) }
                        clearFolderStatus.value =
                            "Cleared $deletedFiles audio files from $folderName" +
                                if (errors.isEmpty()) "" else "\n" + errors.joinToString("\n")
                    }
                }
            }
        } finally {
            clearingFolder.value = false
        }
    }

    suspend fun copyAppStoredMusic() {
        check(copyingMusic.compareAndSet(false, true)) { "Music copy is already running" }
        try {
            g.downloadMutex.withLock {
                g.syncGate.withLock {
                    g.fileGate.withLock {
                        check(!g.resetting.value) { "Library reset is in progress" }
                        requireMusicFolder()
                        val root =
                            g.dao.roots().firstOrNull { it.uri == g.prefs.state.value.musicDir }
                        val tracks =
                            g.dao.tracks().filter {
                                !it.deleted && it.mergedInto.isBlank() && appStoredAudio(it) != null
                            }
                        var copied = 0
                        val errors = mutableListOf<String>()
                        for ((index, track) in tracks.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            copyMusicStatus.value =
                                "Saving ${index + 1}/${tracks.size}: ${track.title}"
                            try {
                                val source = appStoredAudio(track) ?: continue
                                val name =
                                    track.fileName.ifBlank {
                                        safeName(track.title) + "." + source.extension
                                    }
                                val uri =
                                    publish(source, name, dev.sillyplayer.sync.syncAudioMime(name))
                                val updated =
                                    track.copy(
                                        uri = uri,
                                        root = root?.id.orEmpty(),
                                        available = true,
                                    )
                                try {
                                    g.dao.putTrack(updated)
                                } catch (e: Exception) {
                                    DocumentFile.fromSingleUri(g.context, Uri.parse(uri))?.delete()
                                    throw e
                                }
                                // keep the private original so a playing/queued decoder isn't interrupted future app sessions use the verified public copy, IDs and playlists stay intact
                                withContext(Dispatchers.Main) {
                                    g.engine.value?.updateTrack(updated)
                                }
                                copied++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errors += "${track.title}: ${e.message}"
                            }
                        }
                        copyMusicStatus.value =
                            "$copied songs saved to selected folder" +
                                if (errors.isEmpty()) ""
                                else "\n" + errors.take(5).joinToString("\n")
                    }
                }
            }
        } finally {
            copyingMusic.value = false
        }
    }

    suspend fun destination(
        name: String,
        mime: String,
        folder: String = g.prefs.state.value.musicDir,
    ): DocumentFile {
        val root = requireMusicFolder(folder)
        return root.createFile(mime, safeName(name)) ?: error("Could not create destination file")
    }

    /** publish and index under one gate, so folder observers cannot index a partial download */
    suspend fun publishDownload(file: File, name: String, mime: String, folder: String,
        source: Source?, cover: File?, indexed: (Track) -> Unit): String = g.fileGate.withLock {
        val uri = publish(file, name, mime, folder)
        if (source != null) {
            var track = indexUnlocked(uri, source, force = true)
            if (track.art.isBlank() && cover?.isFile == true) {
                val target = File(g.context.filesDir, "art/downloads/${track.id}.jpg")
                target.parentFile?.mkdirs()
                cover.copyTo(target, overwrite = true)
                track = track.copy(art = target.toURI().toString())
                saveTrack(track)
            }
            indexed(track)
        }
        uri
    }

    suspend fun publish(
        file: File,
        name: String,
        mime: String,
        folder: String = g.prefs.state.value.musicDir,
        created: (String) -> Unit = {},
    ): String {
        val target = destination(name, mime, folder)
        try {
            created(target.uri.toString())
            g.output(target.uri.toString()).use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
            require(
                g.input(target.uri.toString()).use { it.sha256() } ==
                    file.inputStream().use { it.sha256() }
            ) {
                "Published file failed checksum verification"
            }
            return target.uri.toString()
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    suspend fun replaceContent(uri: String, file: File) =
        g.fileGate.withLock { replaceContentUnlocked(uri, file) }

    internal suspend fun replaceContentUnlocked(uri: String, file: File) {
        val stage = File(g.context.filesDir, "edits/" + uuid()).also { it.mkdirs() }
        val original = File(stage, "original")
        g.input(uri).use { input -> original.outputStream().use { input.copyTo(it) } }
        File(stage, "recovery.json")
            .writeText(
                buildJsonObject {
                        put("uri", uri)
                        put("hash", original.inputStream().use { it.sha256() })
                    }
                    .toString()
            )
        try {
            g.output(uri).use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
            require(g.input(uri).use { it.sha256() } == file.inputStream().use { it.sha256() }) {
                "Replacement checksum mismatch"
            }
            stage.deleteRecursively()
        } catch (e: Exception) {
            runCatching {
                g.output(uri).use { out -> original.inputStream().use { it.copyTo(out) } }
            }
            throw IOException("Cannot update this audio file: ${e.message}", e)
        }
    }

    suspend fun editTags(
        id: String,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        cover: String = "",
    ) =
        g.fileGate.withLock {
            val t = g.dao.track(id) ?: return@withLock
            val stage = File(g.context.filesDir, "edits/" + uuid()).also { it.mkdirs() }
            val original = File(stage, "original")
            val edited =
                File(stage, "edited." + (document(t.uri)?.name?.substringAfterLast('.') ?: "mp3"))
            g.input(t.uri).use { input -> original.outputStream().use { input.copyTo(it) } }
            original.copyTo(edited)
            val coverFile =
                if (cover.isNotEmpty())
                    File(stage, "cover").also { f ->
                        g.input(cover).use { input -> f.outputStream().use { input.copyTo(it) } }
                    }
                else null
            val tags =
                buildJsonObject {
                        put("title", title)
                        put("artist", artist)
                        put("album", album)
                        put("albumArtist", albumArtist)
                    }
                    .toString()
            require(NativeMedia.edit(edited.path, tags, coverFile?.path ?: "")) {
                "This format cannot be safely edited"
            }
            File(stage, "recovery.json")
                .writeText(
                    buildJsonObject {
                            put("uri", t.uri)
                            put("hash", original.inputStream().use { it.sha256() })
                        }
                        .toString()
                )
            try {
                g.output(t.uri).use { out ->
                    edited.inputStream().use { it.copyTo(out, 256 * 1024) }
                }
                require(
                    g.input(t.uri).use { it.sha256() } == edited.inputStream().use { it.sha256() }
                ) {
                    "Tag write verification failed"
                }
                val updated = indexUnlocked(t.uri, force = true)
                withContext(Dispatchers.Main) { g.engine.value?.updateTrack(updated) }
                stage.deleteRecursively()
            } catch (e: Exception) {
                runCatching {
                    g.output(t.uri).use { out -> original.inputStream().use { it.copyTo(out) } }
                }
                throw IOException(
                    "Could not save embedded tags: ${e.message}. Original recovery copy retained.",
                    e,
                )
            }
        }

    suspend fun batchEditTags(
        ids: List<String>,
        artist: String?,
        album: String?,
        albumArtist: String?,
        cover: String?,
    ): String {
        val uniqueIds = ids.distinct()
        var updated = 0
        var fallbackDbOnly = 0
        for (id in uniqueIds) {
            val t = g.dao.track(id) ?: continue
            if (t.deleted) continue
            val newArtist = artist?.takeIf { it.isNotBlank() } ?: t.artist
            val newAlbum = album?.takeIf { it.isNotBlank() } ?: t.album
            val newAlbumArtist = albumArtist?.takeIf { it.isNotBlank() } ?: t.albumArtist
            val newCover = cover?.takeIf { it.isNotBlank() } ?: ""
            val wroteEmbedded =
                t.available &&
                    t.uri.isNotBlank() &&
                    runCatching {
                        editTags(id, t.title, newArtist, newAlbum, newAlbumArtist, newCover)
                    }.isSuccess
            if (wroteEmbedded) {
                updated++
            } else {
                val patched = t.copy(
                    artist = newArtist,
                    album = newAlbum,
                    albumArtist = newAlbumArtist,
                )
                saveTrack(patched)
                withContext(Dispatchers.Main) { g.engine.value?.updateTrack(patched) }
                updated++
                fallbackDbOnly++
            }
        }
        return "Updated tags for $updated " +
            (if (updated == 1) "song" else "songs") +
            if (fallbackDbOnly > 0) " ($fallbackDbOnly saved in library only)" else ""
    }

    suspend fun cleanTitles(ids: List<String>): String {
        val uniqueIds = ids.distinct()
        var cleaned = 0
        for (id in uniqueIds) {
            val t = g.dao.track(id) ?: continue
            if (t.deleted) continue
            val nextTitle = cleanSongTitle(t.title)
            if (nextTitle == t.title) continue
            val wroteEmbedded =
                t.available &&
                    t.uri.isNotBlank() &&
                    runCatching {
                        editTags(id, nextTitle, t.artist, t.album, t.albumArtist, "")
                    }.isSuccess
            if (!wroteEmbedded) {
                val patched = t.copy(title = nextTitle)
                saveTrack(patched)
                withContext(Dispatchers.Main) { g.engine.value?.updateTrack(patched) }
            }
            cleaned++
        }
        return if (cleaned == 0) "All selected titles are already clean"
        else "Cleaned $cleaned " + if (cleaned == 1) "title" else "titles"
    }

    suspend fun exportPlaylistM3u(collectionId: String, destUri: String): String {
        val allTracks = g.dao.tracks()
        val trackById = allTracks.associateBy { it.id }
        val tracks =
            when (collectionId) {
                "liked" -> {
                    val likedIds = g.dao.likes().mapTo(HashSet()) { it.track }
                    allTracks.filter { it.id in likedIds }
                }
                "recent" -> {
                    g.dao.plays()
                        .sortedByDescending { it.playedAt }
                        .map { it.track }
                        .distinct()
                        .take(100)
                        .mapNotNull { trackById[it] }
                }
                else -> g.dao.members(collectionId).mapNotNull { trackById[it.track] }
            }
        require(tracks.isNotEmpty()) { "No songs to export in this playlist" }
        val content = formatM3u(tracks)
        g.output(destUri).use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
        return "Exported ${tracks.size} " + if (tracks.size == 1) "song" else "songs"
    }

    suspend fun importPlaylistM3u(sourceUri: String): String {
        val content =
            g.input(sourceUri).use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        val entries = parseM3u(content)
        require(entries.isNotEmpty()) { "No songs found in that M3U playlist" }

        val allTracks = g.dao.tracks()
        val byUri = HashMap<String, String>()
        val byFileName = HashMap<String, String>()
        val byArtistTitle = HashMap<String, String>()
        val byTitle = HashMap<String, String>()
        for (t in allTracks) {
            if (t.uri.isNotBlank()) byUri[t.uri.lowercase(java.util.Locale.ROOT)] = t.id
            val fn = t.fileName.ifBlank { document(t.uri)?.name.orEmpty() }.lowercase(java.util.Locale.ROOT)
            if (fn.isNotBlank() && fn !in byFileName) byFileName[fn] = t.id
            val titleKey = t.title.trim().lowercase(java.util.Locale.ROOT)
            val artistKey = t.artist.trim().lowercase(java.util.Locale.ROOT)
            if (titleKey.isNotEmpty()) {
                if (titleKey !in byTitle) byTitle[titleKey] = t.id
                if (artistKey.isNotEmpty()) byArtistTitle["$artistKey|$titleKey"] = t.id
            }
        }

        val matched = ArrayList<String>()
        var unmatched = 0
        for (entry in entries) {
            val rawPath = entry.path.trim()
            val fileName = rawPath.substringAfterLast('/').substringAfterLast('\\').lowercase(java.util.Locale.ROOT)
            var found = byUri[rawPath.lowercase(java.util.Locale.ROOT)]
            if (found == null && fileName.isNotEmpty()) found = byFileName[fileName]
            if (found == null && entry.title.isNotBlank()) {
                val tKey = entry.title.trim().lowercase(java.util.Locale.ROOT)
                val aKey = entry.artist.trim().lowercase(java.util.Locale.ROOT)
                if (aKey.isNotEmpty()) found = byArtistTitle["$aKey|$tKey"]
                if (found == null) found = byTitle[tKey]
            }
            if (found != null) matched += found else unmatched++
        }
        require(matched.isNotEmpty()) {
            "None of the ${entries.size} songs in this playlist matched your library"
        }
        val docName =
            document(sourceUri)?.name?.substringBeforeLast('.')?.trim().orEmpty()
                .ifEmpty { "Imported Playlist" }
        val cid = uuid()
        collection(docName, "playlist", cid, "")
        add(cid, matched, allowDuplicates = true)
        return "Imported \"$docName\" · ${matched.size} " +
            (if (matched.size == 1) "song" else "songs") +
            if (unmatched > 0) " · $unmatched not in library" else ""
    }

    private suspend fun recoverTrash() {
        for (entry in g.dao.trash()) {
            val track = g.dao.track(entry.track) ?: continue
            if (track.deleted) continue
            if (document(entry.original)?.exists() == true) {
                document(entry.stored)?.delete()
                g.dao.deleteTrash(entry.track)
            } else if (
                runCatching { g.input(entry.stored).use { it.sha256() } }.getOrNull() == entry.hash
            ) {
                g.db.withTransaction {
                    g.dao.putTrack(track.copy(deleted = true, available = false))
                    journal("tracks", track.id, null)
                }
            }
        }
    }

    private suspend fun recoverWrites() {
        val publication = File(g.context.filesDir, "restore-publish.json")
        if (publication.exists())
            runCatching {
                    val journal = readRestorePublications(publication)
                    for (uri in journal.uris) if (g.dao.byUri(uri) == null) {
                        val file = document(uri)
                        require(file == null || !file.exists() || file.delete()) {
                            "Cannot remove an interrupted restore file"
                        }
                    }
                    require(journal.problem == null) { journal.problem.orEmpty() }
                    publication.delete()
                }
                .onFailure {
                    status.value = "Interrupted restore cleanup needs attention: ${it.message}"
                }

        val root = File(g.context.filesDir, "edits")
        for (dir in root.listFiles().orEmpty()) {
            val record = File(dir, "recovery.json")
            if (!record.isFile) continue
            runCatching {
                    val j = JSONObject(record.readText())
                    val original = File(dir, "original")
                    require(original.inputStream().use { it.sha256() } == j.getString("hash"))
                    g.output(j.getString("uri")).use { out ->
                        original.inputStream().use { it.copyTo(out) }
                    }
                    dir.deleteRecursively()
                }
                .onFailure {
                    status.value = "A metadata recovery copy needs attention: ${it.message}"
                }
        }
    }

    suspend fun trash(ids: List<String>) =
        g.fileGate.withLock {
            var bin: DocumentFile? = null
            for (id in ids.distinct()) {
                val t = g.dao.track(id) ?: continue
                if (t.deleted) continue
                val original = if (t.uri.isBlank()) null else runCatching { document(t.uri) }.getOrNull()
                val readable = original != null && runCatching {
                    original.exists() && original.canRead() && g.input(t.uri).use { it.read() >= 0 }
                }.getOrDefault(false)
                if (t.removeWithoutAudio(readable)) {
                    // no audio lives here: removing the record needs no folder/storage grant
                    g.db.withTransaction {
                        g.dao.putTrack(t.removedFromLibrary())
                        journal("tracks", id, null)
                    }
                    withContext(Dispatchers.Main) { g.engine.value?.removeTracks(setOf(id)) }
                    continue
                }
                requireNotNull(original)
                require(original.canWrite()) { "${t.title}: file is read-only; grant write access to its folder" }
                if (bin == null) {
                    val root = requireMusicFolder()
                    bin = root.findFile(".sillyplayer-trash") ?: root.createDirectory(".sillyplayer-trash")
                        ?: error("Cannot create Trash")
                }
                val originalName = original.name ?: t.title
                val stored =
                    requireNotNull(bin).createFile(
                        original.type ?: "application/octet-stream",
                        id + "-" + safeName(original.name ?: t.title),
                    ) ?: error("Cannot create Trash file")
                var originalRemoved = false
                try {
                    g.input(t.uri).use { input ->
                        g.output(stored.uri.toString()).use { input.copyTo(it, 256 * 1024) }
                    }
                    val hash = g.input(stored.uri.toString()).use { it.sha256() }
                    require(hash == g.input(t.uri).use { it.sha256() })
                    g.dao.putTrash(TrashEntry(id, t.uri, stored.uri.toString(), originalName, hash))
                    require(original.delete()) {
                        "Provider refused to remove the original; it was left in place"
                    }
                    originalRemoved = true
                    g.db.withTransaction {
                        g.dao.putTrash(
                            TrashEntry(id, t.uri, stored.uri.toString(), originalName, hash)
                        )
                        g.dao.putTrack(t.removedFromLibrary())
                        journal("tracks", id, null)
                    }
                    withContext(Dispatchers.Main) { g.engine.value?.removeTracks(setOf(id)) }
                } catch (e: Exception) {
                    if (!originalRemoved) {
                        stored.delete()
                        g.dao.deleteTrash(id)
                    }
                    throw e
                }
            }
        }

    suspend fun restoreTrash(entry: TrashEntry) =
        g.fileGate.withLock {
            val stored = document(entry.stored) ?: error("Trash file is unavailable")
            require(g.input(entry.stored).use { it.sha256() } == entry.hash) {
                "Trash file checksum changed"
            }
            val target = destination(entry.name, stored.type ?: "audio/mpeg")
            g.input(entry.stored).use { input ->
                g.output(target.uri.toString()).use { input.copyTo(it) }
            }
            if (g.input(target.uri.toString()).use { it.sha256() } != entry.hash) {
                target.delete()
                error("Trash restore failed its checksum; original kept in Trash")
            }
            val old = g.dao.track(entry.track) ?: error("Song record missing")
            saveTrack(
                old.copy(
                    uri = target.uri.toString(),
                    available = true,
                    deleted = false,
                    mergedInto = "",
                )
            )
            stored.delete()
            g.dao.deleteTrash(entry.track)
        }

    suspend fun emptyTrash() =
        g.fileGate.withLock {
            for (entry in g.dao.trash()) {
                require(document(entry.stored)?.delete() == true) {
                    "Could not empty ${entry.name}"
                }
                g.dao.deleteTrash(entry.track)
            }
        }

    suspend fun duplicates(): List<List<Track>> {
        val rows = g.dao.tracks()
        val fresh =
            rows.mapNotNull { track ->
                if (!track.available || track.uri.isBlank()) return@mapNotNull null
                val hash =
                    if (track.hash.matches(Regex("[a-f0-9]{64}"))) {
                        // reuse the cached hash when the file is still readable and unmodified
                        val readable = runCatching { g.input(track.uri).use { it.read() >= 0 } }.getOrDefault(false)
                        if (!readable) return@mapNotNull null
                        track.hash
                    } else {
                        runCatching { g.input(track.uri).use { it.sha256() } }.getOrNull() ?: return@mapNotNull null
                    }
                if (hash != track.hash) track.copy(hash = hash).also { g.dao.putTrack(it) }
                else track
            }
        return fresh
            .filter { it.hash.isNotBlank() && it.available }
            .groupBy { it.hash }
            .values
            .filter { it.size > 1 }
    }

    suspend fun consolidate(keep: String, remove: List<String>) {
        require(keep !in remove)
        val keeper = g.dao.track(keep) ?: error("Kept song is unavailable")
        val loaded = withContext(Dispatchers.Main) { g.engine.value?.state?.value?.music?.id }
        require(loaded !in remove) { "Keep the loaded song before consolidating this group" }
        require(g.input(keeper.uri).use { it.sha256() } == keeper.hash) {
            "The kept file changed; scan again"
        }
        for (id in remove) {
            val track = g.dao.track(id) ?: error("Duplicate no longer exists")
            require(
                track.hash == keeper.hash && g.input(track.uri).use { it.sha256() } == keeper.hash
            ) {
                "A duplicate changed; scan again"
            }
        }
        for (id in remove) {
            g.db.withTransaction {
                for (m in g.dao.members().filter { it.track == id }) {
                    val value = m.copy(track = keep)
                    g.dao.putMember(value)
                    journal("members", value.id, JsonCodec.encodeToJsonElement(value))
                }
                if (g.dao.likes().any { it.track == id }) {
                    if (g.dao.likes().none { it.track == keep }) like(keep)
                    g.dao.deleteLike(id)
                    journal("likes", id, null)
                }
                for (c in g.dao.counters().filter { it.track == id }) {
                    val value = c.copy(track = keep)
                    g.dao.putCounter(value)
                    if (value.device == g.prefs.deviceId)
                        journal(
                            "counters",
                            value.device + ":" + value.component,
                            JsonCodec.encodeToJsonElement(value),
                        )
                }
                for (play in g.dao.plays().filter { it.track == id }) {
                    val value = play.copy(track = keep)
                    g.dao.putPlay(value)
                    journal("plays", value.session, JsonCodec.encodeToJsonElement(value))
                }
            }
        }
        withContext(Dispatchers.Main) {
            g.engine.value?.remapTracks(remove.associateWith { keeper })
        }
        trash(remove)
        for (id in remove) g.dao.track(id)?.let { g.dao.putTrack(it.copy(mergedInto = keep)) }
    }

    suspend fun locate(id: String, uri: String) =
        g.fileGate.withLock {
            val old = g.dao.track(id) ?: error("Song not found")
            val hash = g.input(uri).use { it.sha256() }
            require(old.hash.isBlank() || hash == old.hash) {
                "The selected file is not an exact match"
            }
            saveTrack(old.copy(uri = uri, available = true, deleted = false, hash = hash))
        }
}
