package dev.sillyplayer.data

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.sillyplayer.Graph
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val MAX_RECOVERY_BYTES = 16 * 1024 * 1024
private const val MAX_SQLITE_RECOVERY_BYTES = 128L * 1024 * 1024
private const val RECOVERY_FORMAT = "SillyPlayer library recovery"
private const val PORTABLE_DB_FORMAT = "SillyPlayer SQLite Library"
private const val PORTABLE_DB_MIME = "application/vnd.sillyplayer.database"
private const val STRUCTURAL_SAVE_DEBOUNCE_MS = 10_000L
private const val STATS_SAVE_INTERVAL_MS = 15L * 60L * 1000L
private val HEX_64_REGEX = Regex("[a-f0-9]{64}")
private val SQLITE_SLOT_NAMES = listOf(".sillyplayer-library-0.sqlite3", ".sillyplayer-library-1.sqlite3")
private val LEGACY_JSON_SLOT_NAMES = listOf(".sillyplayer-library-0.json", ".sillyplayer-library-1.json")

@Serializable
internal data class PortableSettings(
    val palette: String = "Catppuccin",
    val light: Boolean = false,
    val cropArtwork: Boolean = true,
    val master: Float = 100f,
    val onlineVolume: Float = 100f,
    val mode: Int = 0,
    val eqEnabled: Boolean = false,
    val eq: List<Float> = List(10) { 0f },
    val eqPreset: String = "Flat",
    val eqPresets: List<EqPreset> = emptyList(),
    val eqStateUpdatedAt: Long = 0L,
    val pauseFade: Boolean = false,
    val fadeMs: Int = 300,
    val crossfadeMs: Int = 0,
    val smartShuffle: Boolean = false,
    val duckAudio: Boolean = true,
    val allowPlaylistDuplicates: Boolean = false,
    val updateNotifications: Boolean = true,
    val pinnedPlaylists: List<String> = emptyList(),
    val pinnedArtists: List<String> = emptyList(),
) {
    fun applyTo(prefs: Preferences): Preferences =
        prefs.copy(
            palette = palette.ifBlank { prefs.palette },
            light = light,
            cropArtwork = cropArtwork,
            master = master.takeIf { it.isFinite() }?.coerceIn(0f, 300f) ?: prefs.master,
            onlineVolume = onlineVolume.takeIf { it.isFinite() }?.coerceIn(0f, 300f) ?: prefs.onlineVolume,
            mode = mode.coerceIn(0, 2),
            eqEnabled = eqEnabled,
            eq =
                if (eq.size == 10 && eq.all { it.isFinite() }) eq.map { it.coerceIn(-12f, 12f) }
                else prefs.eq,
            eqPreset = eqPreset.ifBlank { prefs.eqPreset },
            eqPresets = mergeEqPresets(prefs.eqPresets, eqPresets),
            eqStateUpdatedAt = maxOf(prefs.eqStateUpdatedAt, eqStateUpdatedAt),
            pauseFade = pauseFade,
            fadeMs = fadeMs.coerceIn(50, 5000),
            crossfadeMs = crossfadeMs.coerceIn(0, 8000),
            smartShuffle = smartShuffle,
            duckAudio = duckAudio,
            allowPlaylistDuplicates = allowPlaylistDuplicates,
            updateNotifications = updateNotifications,
            pinnedPlaylists = pinnedPlaylists.distinct(),
            pinnedArtists = pinnedArtists.distinct(),
        )

    companion object {
        fun from(prefs: Preferences): PortableSettings =
            PortableSettings(
                palette = prefs.palette,
                light = prefs.light,
                cropArtwork = prefs.cropArtwork,
                master = prefs.master,
                onlineVolume = prefs.onlineVolume,
                mode = prefs.mode,
                eqEnabled = prefs.eqEnabled,
                eq = prefs.eq,
                eqPreset = prefs.eqPreset,
                eqPresets = prefs.eqPresets,
                eqStateUpdatedAt = prefs.eqStateUpdatedAt,
                pauseFade = prefs.pauseFade,
                fadeMs = prefs.fadeMs,
                crossfadeMs = prefs.crossfadeMs,
                smartShuffle = prefs.smartShuffle,
                duckAudio = prefs.duckAudio,
                allowPlaylistDuplicates = prefs.allowPlaylistDuplicates,
                updateNotifications = prefs.updateNotifications,
                pinnedPlaylists = prefs.pinnedPlaylists,
                pinnedArtists = prefs.pinnedArtists,
            )
    }
}

@Serializable
internal data class RecoveryLibrary(
    val created: Long,
    val tracks: List<Track>,
    val collections: List<Collection>,
    val members: List<Member>,
    val likes: List<Like>,
    val counters: List<Counter> = emptyList(),
    val plays: List<Play> = emptyList(),
    val jobs: List<DownloadJob> = emptyList(),
    val downloadMembers: List<DownloadMember> = emptyList(),
    val searches: List<Search> = emptyList(),
    val restoreIds: List<RestoreId> = emptyList(),
    val settings: PortableSettings? = null,
)

private fun recoveryDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun encodeRecovery(value: RecoveryLibrary): ByteArray {
    val body = JsonCodec.encodeToJsonElement(value)
    val encoded = buildJsonObject {
        put("format", RECOVERY_FORMAT)
        put("version", 1)
        put("sha256", recoveryDigest(body.toString().toByteArray()))
        put("library", body)
    }.toString().toByteArray()
    require(encoded.size <= MAX_RECOVERY_BYTES) { "Library recovery metadata exceeds 16 MiB; export a ZIP backup" }
    return encoded
}

internal fun decodeRecovery(bytes: ByteArray): RecoveryLibrary {
    require(bytes.size <= MAX_RECOVERY_BYTES) { "Recovery file is too large" }
    val envelope = JsonCodec.parseToJsonElement(bytes.decodeToString()).jsonObject
    require(envelope["format"]?.jsonPrimitive?.content == RECOVERY_FORMAT &&
        envelope["version"]?.jsonPrimitive?.int == 1) { "Unsupported library recovery format" }
    val body = envelope.getValue("library")
    require(envelope["sha256"]?.jsonPrimitive?.content == recoveryDigest(body.toString().toByteArray())) {
        "Library recovery checksum mismatch"
    }
    val library = JsonCodec.decodeFromJsonElement<RecoveryLibrary>(body)
    validateRecoveryLibrary(library)
    return library
}

internal fun validateRecoveryLibrary(library: RecoveryLibrary) {
    require(library.created > 0 && library.tracks.size <= 100000 && library.members.size <= 100000) {
        "Invalid library recovery metadata"
    }
    val trackIds = library.tracks.map { it.id }.toSet()
    val collectionIds = library.collections.map { it.id }.toSet()
    require(trackIds.size == library.tracks.size && collectionIds.size == library.collections.size &&
        library.members.map { it.id }.toSet().size == library.members.size) { "Duplicate recovery identity" }
    require((trackIds + collectionIds + library.members.map { it.id }).all { it.isNotBlank() && it.length <= 200 }) {
        "Invalid recovery identity"
    }
    require(library.tracks.all {
        it.speed.isFinite() && it.speed in .5f..2f && it.volumePercent.isFinite() &&
            it.volumePercent in 0f..300f && (it.hash.isEmpty() || HEX_64_REGEX.matches(it.hash))
    }) { "Invalid recovered song settings" }
    require(library.members.all { it.track in trackIds && it.collection in collectionIds } &&
        library.likes.all { it.track in trackIds } && library.counters.all { it.track in trackIds } &&
        library.plays.all { it.track in trackIds }) { "Broken recovery reference" }
}

/** keep edited metadata when verified audio repairs an unavailable row, across process restarts */
internal fun recoverIndexedMetadata(indexed: Track, previous: Track?): Track {
    if (previous == null || previous.available || previous.hash.isEmpty() || previous.hash != indexed.hash)
        return indexed
    return previous.copy(uri = indexed.uri, art = indexed.art, artHash = indexed.artHash,
        available = true, deleted = false, root = indexed.root, modified = indexed.modified,
        size = indexed.size, hash = indexed.hash, fileName = indexed.fileName, managed = indexed.managed)
}

// mirrors the SQLite library DB and settings into the chosen audio folder as rotating .sillyplayer-library-*.sqlite3 files so it survives uninstalling (also reads legacy .json snapshots)
class LibraryRecovery(private val g: Graph) {
    val status = MutableStateFlow("")
    val lastSavedAt = MutableStateFlow(0L)
    val saving = MutableStateFlow(false)
    @Volatile var lastWriteCompletedAt: Long = 0L
        private set
    @Volatile private var dirty = false
    @Volatile private var statsDirty = false
    @Volatile private var lastStatsSaveAt = 0L
    private val gate = Mutex()
    private var cachedFolder = ""
    private var sqliteSlots = arrayOfNulls<DocumentFile>(2)
    private var legacyJsonSlots = arrayOfNulls<DocumentFile>(2)
    private var newestSlot = -1
    private var newestCreated = 0L
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    fun requestSave() {
        dirty = true
        requests.tryEmit(Unit)
    }

    /** flush immediately (e.g. on app backgrounding or after clearing the folder) */
    fun flushNow(allowEmpty: Boolean = false) {
        g.scope.launch {
            if (g.resetting.value) return@launch
            if (!allowEmpty && !dirty && !statsDirty && lastSavedAt.value > 0L) return@launch
            runCatching { save(allowEmpty) }.onFailure { error ->
                if (error is CancellationException) throw error
                status.value = "Library database sync: ${error.message}. ZIP backup is also available."
            }
        }
    }

    suspend fun saveNow(allowEmpty: Boolean = false) {
        if (g.resetting.value) return
        save(allowEmpty)
    }

    @OptIn(FlowPreview::class)
    fun observe() {
        g.scope.launch {
            runCatching {
                g.prefs.loaded.await()
                val folder = g.prefs.state.value.musicDir
                if (folder.isNotBlank()) {
                    var needsInitialSave = false
                    gate.withLock {
                        // read only portable metadata on cold start instead of full integrity_check + loading all tables
                        val existingCreated = runCatching { inspectCreated(folder) }.getOrNull()
                        if (existingCreated != null && existingCreated > lastSavedAt.value) {
                            lastSavedAt.value = existingCreated
                        } else if (existingCreated == null) {
                            needsInitialSave = g.dao.hasAnyTracks() || g.dao.hasAnyCollections()
                        }
                    }
                    if (needsInitialSave) requestSave()
                }
            }
        }
        g.scope.launch {
            // drop the initial invalidation emission so cold launch doesn't immediately rewrite an unchanged mirror
            val dbChanges =
                g.db.invalidationTracker
                    .createFlow(
                        "tracks",
                        "collections",
                        "members",
                        "likes",
                        "jobs",
                        "searches",
                        "restore_ids",
                    )
                    .drop(1)
                    .onEach { dirty = true }
                    .map { Unit }
            val prefChanges =
                g.prefs.state
                    .map { PortableSettings.from(it) }
                    .distinctUntilChanged()
                    .drop(1)
                    .onEach { dirty = true }
                    .map { Unit }
            merge(dbChanges, prefChanges, requests)
                .debounce(STRUCTURAL_SAVE_DEBOUNCE_MS)
                .collect {
                    while (g.library.isIndexing || g.backup.state.value.busy || g.sync.state.value.busy)
                        delay(1000)
                    if (!g.resetting.value) runCatching { save(allowEmpty = false) }.onFailure { error ->
                        if (error is CancellationException) throw error
                        status.value = "Library database sync: ${error.message}. ZIP backup is also available."
                    }
                }
        }
        g.scope.launch {
            // listening stats change often during playback so mirror them on a low-frequency cadence (plus onStop flush)
            g.db.invalidationTracker
                .createFlow("plays", "counters")
                .drop(1)
                .collect {
                    statsDirty = true
                    dirty = true
                    val now = SystemClock.elapsedRealtime()
                    if (lastStatsSaveAt == 0L) {
                        lastStatsSaveAt = now
                        return@collect
                    }
                    if (now - lastStatsSaveAt < STATS_SAVE_INTERVAL_MS) return@collect
                    while (g.library.isIndexing || g.backup.state.value.busy || g.sync.state.value.busy)
                        delay(1000)
                    if (!g.resetting.value && (dirty || statsDirty)) {
                        runCatching { save(allowEmpty = false) }.onFailure { error ->
                            if (error is CancellationException) throw error
                            status.value = "Library database sync: ${error.message}. ZIP backup is also available."
                        }
                    }
                }
        }
    }

    private fun readLegacyJson(file: DocumentFile): RecoveryLibrary {
        val bytes = g.input(file.uri.toString()).use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(65536)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                require(output.size() + count <= MAX_RECOVERY_BYTES) { "Recovery file is too large" }
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        return decodeRecovery(bytes)
    }

    private fun readSqlite(file: DocumentFile): RecoveryLibrary {
        val temp = File(g.context.cacheDir, "db-recover-${uuid()}.sqlite3")
        try {
            var total = 0L
            g.input(file.uri.toString()).use { input ->
                temp.outputStream().use { out ->
                    val block = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(block)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_SQLITE_RECOVERY_BYTES) { "Library database file is too large" }
                        out.write(block, 0, n)
                    }
                }
            }
            require(total >= 100) { "Library database file is incomplete" }
            return readPortableSqlite(temp)
        } finally {
            temp.delete()
            File(temp.absolutePath + "-wal").delete()
            File(temp.absolutePath + "-shm").delete()
            File(temp.absolutePath + "-journal").delete()
        }
    }

    private fun readPortableSqlite(file: File): RecoveryLibrary {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val integrity =
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else ""
                }
            require(integrity.equals("ok", ignoreCase = true)) { "Library database integrity check failed" }
            val meta = mutableMapOf<String, String>()
            db.rawQuery("SELECT key, value FROM sillyplayer_portable_meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            require(meta["format"] == PORTABLE_DB_FORMAT && meta["version"] == "1") {
                "Unsupported library database format"
            }
            val created = meta["created"]?.toLongOrNull() ?: error("Missing database timestamp")
            val settings =
                meta["settings"]?.let {
                    runCatching { JsonCodec.decodeFromString<PortableSettings>(it) }.getOrNull()
                }
            val tracks = mutableListOf<Track>()
            db.rawQuery(
                "SELECT id,uri,title,artist,album,albumArtist,genre,year,number,duration,art,available,deleted,artHash,fileName,added,modified,size,hash,speed,nightcore,volumePercent,sourceUrl,uploader,videoTitle,sourceProvider,sourceItemId,mergedInto,managed,root FROM tracks",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    tracks +=
                        Track(
                            id = c.getString(0),
                            uri = c.getString(1),
                            title = c.getString(2),
                            artist = c.getString(3),
                            album = c.getString(4),
                            albumArtist = c.getString(5),
                            genre = c.getString(6),
                            year = c.getInt(7),
                            number = c.getInt(8),
                            duration = c.getDouble(9),
                            art = c.getString(10),
                            available = c.getInt(11) != 0,
                            deleted = c.getInt(12) != 0,
                            artHash = c.getString(13),
                            fileName = c.getString(14),
                            added = c.getLong(15),
                            modified = c.getLong(16),
                            size = c.getLong(17),
                            hash = c.getString(18),
                            speed = c.getFloat(19),
                            nightcore = c.getInt(20) != 0,
                            volumePercent = c.getFloat(21),
                            sourceUrl = c.getString(22),
                            uploader = c.getString(23),
                            videoTitle = c.getString(24),
                            sourceProvider = c.getString(25),
                            sourceItemId = c.getString(26),
                            mergedInto = c.getString(27),
                            managed = c.getInt(28) != 0,
                            root = c.getString(29),
                        )
                }
            }
            val collections = mutableListOf<Collection>()
            db.rawQuery("SELECT id,name,kind,art,artHash FROM collections", null).use { c ->
                while (c.moveToNext()) {
                    collections += Collection(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
                }
            }
            val members = mutableListOf<Member>()
            db.rawQuery("SELECT id,collection,track,position FROM members", null).use { c ->
                while (c.moveToNext()) {
                    members += Member(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
                }
            }
            val likes = mutableListOf<Like>()
            db.rawQuery("SELECT track FROM likes", null).use { c ->
                while (c.moveToNext()) likes += Like(c.getString(0))
            }
            val counters = mutableListOf<Counter>()
            db.rawQuery("SELECT device,track,milliseconds,plays,lastPlayed,component FROM counters", null).use { c ->
                while (c.moveToNext()) {
                    counters += Counter(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getString(5))
                }
            }
            val plays = mutableListOf<Play>()
            db.rawQuery("SELECT session,track,playedAt,heardMs,qualified FROM plays", null).use { c ->
                while (c.moveToNext()) {
                    plays += Play(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0)
                }
            }
            val jobs = mutableListOf<DownloadJob>()
            runCatching {
                db.rawQuery(
                    "SELECT id,provider,sourceId,url,title,uploader,artist,art,kind,destination,state,bytes,total,progress,error,output,stage,created,refreshes FROM jobs",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val rawState = c.getString(10)
                        val safeState =
                            if (rawState in listOf("Queued", "Resolving", "Active", "Post-processing")) "Interrupted"
                            else rawState
                        jobs +=
                            DownloadJob(
                                id = c.getString(0),
                                provider = c.getString(1),
                                sourceId = c.getString(2),
                                url = c.getString(3),
                                title = c.getString(4),
                                uploader = c.getString(5),
                                artist = c.getString(6),
                                art = c.getString(7),
                                kind = c.getString(8),
                                destination = c.getString(9),
                                state = safeState,
                                bytes = c.getLong(11),
                                total = c.getLong(12),
                                progress = c.getFloat(13),
                                error = c.getString(14),
                                output = c.getString(15),
                                stage = c.getString(16),
                                created = c.getLong(17),
                                refreshes = c.getInt(18),
                            )
                    }
                }
            }
            val downloadMembers = mutableListOf<DownloadMember>()
            runCatching {
                db.rawQuery("SELECT id,collection,job,position FROM download_members", null).use { c ->
                    while (c.moveToNext()) {
                        downloadMembers += DownloadMember(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
                    }
                }
            }
            val searches = mutableListOf<Search>()
            runCatching {
                db.rawQuery("SELECT provider,query,used FROM searches", null).use { c ->
                    while (c.moveToNext()) searches += Search(c.getString(0), c.getString(1), c.getLong(2))
                }
            }
            val restoreIds = mutableListOf<RestoreId>()
            runCatching {
                db.rawQuery("SELECT origin,kind,remote,local FROM restore_ids", null).use { c ->
                    while (c.moveToNext()) restoreIds += RestoreId(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
                }
            }
            val trackIds = tracks.mapTo(HashSet()) { it.id }
            val collectionIds = collections.mapTo(HashSet()) { it.id }
            val library =
                RecoveryLibrary(
                    created = created,
                    tracks = tracks,
                    collections = collections,
                    members = members.filter { it.track in trackIds && it.collection in collectionIds },
                    likes = likes.filter { it.track in trackIds },
                    counters = counters.filter { it.track in trackIds },
                    plays = plays.filter { it.track in trackIds },
                    jobs = jobs,
                    downloadMembers = downloadMembers,
                    searches = searches,
                    restoreIds = restoreIds,
                    settings = settings,
                )
            validateRecoveryLibrary(library)
            return library
        }
    }

    private fun readSqliteCreated(file: DocumentFile): Long {
        val temp = File(g.context.cacheDir, "db-meta-${uuid()}.sqlite3")
        try {
            var total = 0L
            g.input(file.uri.toString()).use { input ->
                temp.outputStream().use { out ->
                    val block = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(block)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_SQLITE_RECOVERY_BYTES) { "Library database file is too large" }
                        out.write(block, 0, n)
                    }
                }
            }
            require(total >= 100) { "Library database file is incomplete" }
            SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val meta = mutableMapOf<String, String>()
                db.rawQuery("SELECT key, value FROM sillyplayer_portable_meta", null).use { c ->
                    while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
                }
                require(meta["format"] == PORTABLE_DB_FORMAT && meta["version"] == "1") {
                    "Unsupported library database format"
                }
                return meta["created"]?.toLongOrNull() ?: error("Missing database timestamp")
            }
        } finally {
            temp.delete()
            File(temp.absolutePath + "-wal").delete()
            File(temp.absolutePath + "-shm").delete()
            File(temp.absolutePath + "-journal").delete()
        }
    }

    private fun scanSlots(folder: String, root: DocumentFile) {
        cachedFolder = folder
        sqliteSlots = arrayOfNulls(2)
        legacyJsonSlots = arrayOfNulls(2)
        newestSlot = -1
        newestCreated = 0
        val uri = root.uri
        if (uri.scheme == "content") {
            // query document IDs and names in one SAF cursor instead of root.listFiles() (N+1 Binder IPCs)
            val children =
                runCatching {
                    DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
                }.getOrNull()
            if (children != null) {
                val projection =
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                val queried =
                    runCatching {
                        g.context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val name = c.getString(1) ?: continue
                                val sqliteIdx = SQLITE_SLOT_NAMES.indexOfFirst { name == it || name.startsWith("$it.") }
                                val jsonIdx = if (sqliteIdx < 0) LEGACY_JSON_SLOT_NAMES.indexOf(name) else -1
                                if (sqliteIdx < 0 && jsonIdx < 0) continue
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0))
                                val doc = DocumentFile.fromSingleUri(g.context, childUri) ?: continue
                                if (sqliteIdx >= 0) sqliteSlots[sqliteIdx] = doc
                                else legacyJsonSlots[jsonIdx] = doc
                            }
                            true
                        } ?: false
                    }.getOrDefault(false)
                if (queried) return
            }
        }
        root.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            SQLITE_SLOT_NAMES.indexOfFirst { name == it || name.startsWith("$it.") }
                .takeIf { it >= 0 }
                ?.let { sqliteSlots[it] = file }
            LEGACY_JSON_SLOT_NAMES.indexOf(name)
                .takeIf { it >= 0 }
                ?.let { legacyJsonSlots[it] = file }
        }
    }

    private fun inspectCreated(folder: String): Long? {
        val root = g.library.requireMusicFolder(folder)
        if (cachedFolder != folder) scanSlots(folder, root)
        var newest = 0L
        for ((index, file) in sqliteSlots.withIndex()) {
            if (file == null) continue
            val created = runCatching { readSqliteCreated(file) }.getOrNull() ?: continue
            if (created > newest) {
                newest = created
                newestSlot = index
                newestCreated = created
            }
        }
        if (newest == 0L) {
            for (file in legacyJsonSlots) {
                if (file == null) continue
                val library = runCatching { readLegacyJson(file) }.getOrNull() ?: continue
                if (library.created > newest) {
                    newest = library.created
                    newestCreated = library.created
                }
            }
        }
        return newest.takeIf { it > 0L }
    }

    private fun open(folder: String, readExisting: Boolean = false): Pair<DocumentFile, RecoveryLibrary?> {
        val root = g.library.requireMusicFolder(folder)
        if (cachedFolder == folder && !readExisting) return root to null
        if (cachedFolder != folder) {
            scanSlots(folder, root)
        }
        var newest: RecoveryLibrary? = null
        var invalid = false
        for ((index, file) in sqliteSlots.withIndex()) {
            if (file == null) continue
            val library = runCatching { readSqlite(file) }.getOrNull()
            if (library == null) invalid = true
            else if (newest == null || library.created > newest.created) {
                newest = library
                newestSlot = index
                newestCreated = library.created
            }
        }
        if (newest == null) {
            for (file in legacyJsonSlots) {
                if (file == null) continue
                val library = runCatching { readLegacyJson(file) }.getOrNull()
                if (library == null) invalid = true
                else if (newest == null || library.created > newest.created) {
                    newest = library
                    newestCreated = library.created
                }
            }
        }
        if (newest == null && invalid) {
            cachedFolder = "" // do not later overwrite a damaged database through the cache fast path
            error("Existing library database files are damaged; restore a ZIP backup")
        }
        return root to newest
    }

    /** called before indexing or writing a newly selected folder; never replace a nonempty library */
    suspend fun prepareFolder(folder: String): Int = gate.withLock {
        val saved = try { open(folder, readExisting = true).second } catch (error: Exception) {
            if (error is CancellationException) throw error
            status.value = "${error.message}. Existing database files were kept."
            return@withLock 0
        }
        if (saved == null) return@withLock 0
        var restored = 0
        var didRestore = false
        g.db.withTransaction {
            if (g.dao.hasAnyTracks() || g.dao.hasAnyCollections()) return@withTransaction
            didRestore = true
            for (track in saved.tracks) {
                val uri = track.uri.takeIf { it.startsWith("content:") } ?: "recovery://song/${track.id}"
                g.dao.putTrack(track.copy(uri = uri, available = false, root = "", art = "", modified = 0))
            }
            for (collection in saved.collections) g.dao.putCollection(collection.copy(art = ""))
            for (member in saved.members) g.dao.putMember(member)
            for (like in saved.likes) g.dao.putLike(like)
            for (counter in saved.counters) g.dao.putCounter(counter)
            for (play in saved.plays) g.dao.putPlay(play)
            for (job in saved.jobs) g.dao.putJob(job)
            for (dm in saved.downloadMembers) g.dao.putDownloadMember(dm)
            for (search in saved.searches) g.dao.putSearch(search)
            for (restoreId in saved.restoreIds) g.dao.putRestored(restoreId)
            restored = saved.tracks.count { !it.deleted && it.mergedInto.isBlank() }
        }
        if (didRestore) {
            saved.settings?.let { portable -> g.prefs.update { portable.applyTo(it) } }
            status.value =
                if (restored > 0 || saved.collections.isNotEmpty())
                    "Recovered library database ($restored songs, ${saved.collections.size} playlists). Checking local audio…"
                else "Recovered library database settings"
        }
        restored
    }

    private suspend fun buildPortableSqliteSnapshot(stage: File, created: Long, settingsJson: String) {
        if (stage.exists()) stage.delete()
        val writable = g.db.openHelper.writableDatabase
        val vacuumed =
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    val escaped = stage.absolutePath.replace("'", "''")
                    writable.execSQL("VACUUM INTO '$escaped'")
                    stage.isFile && stage.length() >= 100
                }.getOrDefault(false)
            } else false
        if (!vacuumed) {
            if (stage.exists()) stage.delete()
            g.db.withTransaction {
                writable.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                g.context.getDatabasePath("library.sqlite3").copyTo(stage, overwrite = true)
            }
        }
        SQLiteDatabase.openDatabase(stage.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { snap ->
            snap.beginTransaction()
            try {
                for (table in listOf("peers", "outbox", "replicas", "conflicts", "roots", "trash")) {
                    snap.execSQL("DELETE FROM $table")
                }
                snap.execSQL("UPDATE tracks SET art=''")
                snap.execSQL("UPDATE collections SET art=''")
                snap.execSQL(
                    "CREATE TABLE IF NOT EXISTS sillyplayer_portable_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                )
                snap.execSQL(
                    "INSERT OR REPLACE INTO sillyplayer_portable_meta(key, value) VALUES('format', ?)",
                    arrayOf(PORTABLE_DB_FORMAT),
                )
                snap.execSQL(
                    "INSERT OR REPLACE INTO sillyplayer_portable_meta(key, value) VALUES('version', '1')"
                )
                snap.execSQL(
                    "INSERT OR REPLACE INTO sillyplayer_portable_meta(key, value) VALUES('created', ?)",
                    arrayOf(created.toString()),
                )
                snap.execSQL(
                    "INSERT OR REPLACE INTO sillyplayer_portable_meta(key, value) VALUES('settings', ?)",
                    arrayOf(settingsJson),
                )
                snap.setTransactionSuccessful()
            } finally {
                snap.endTransaction()
            }
            snap.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            val integrity =
                snap.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else ""
                }
            require(integrity.equals("ok", ignoreCase = true)) {
                "Library database integrity check failed"
            }
        }
        File(stage.absolutePath + "-wal").delete()
        File(stage.absolutePath + "-shm").delete()
        File(stage.absolutePath + "-journal").delete()
    }

    private suspend fun save(allowEmpty: Boolean) = gate.withLock {
        g.prefs.loaded.await()
        val folder = g.prefs.state.value.musicDir
        if (folder.isBlank()) return@withLock
        saving.value = true
        try {
            val (root, _) = open(folder) // validate any existing snapshot before selecting a write slot
            if (!allowEmpty) {
                val hasData = g.db.withTransaction {
                    g.dao.hasAnyTracks() || g.dao.hasAnyCollections()
                }
                // an empty new installation must not erase a surviving database before folder recovery
                if (!hasData) return@withLock
            }
            val created = maxOf(System.currentTimeMillis(), newestCreated + 1)
            val settingsJson = JsonCodec.encodeToString(PortableSettings.from(g.prefs.state.value))
            val stage = File(g.context.cacheDir, "db-export-${uuid()}.sqlite3")
            try {
                buildPortableSqliteSnapshot(stage, created, settingsJson)
                val expectedHash = stage.inputStream().use { it.sha256() }
                val targetIndex = if (newestSlot == 0) 1 else 0
                val target =
                    sqliteSlots[targetIndex]
                        ?: root.createFile(PORTABLE_DB_MIME, SQLITE_SLOT_NAMES[targetIndex])
                            ?.also { sqliteSlots[targetIndex] = it }
                        ?: error("Cannot create library database file in audio folder")
                g.output(target.uri.toString()).use { out ->
                    stage.inputStream().use { it.copyTo(out, 256 * 1024) }
                    out.flush()
                }
                require(g.input(target.uri.toString()).use { it.sha256() } == expectedHash) {
                    "Cannot verify library database file in audio folder"
                }
                newestSlot = targetIndex
                newestCreated = created
                lastSavedAt.value = created
                dirty = false
                statsDirty = false
                lastStatsSaveAt = SystemClock.elapsedRealtime()
                status.value = "Library database saved in your audio folder"
            } finally {
                stage.delete()
                File(stage.absolutePath + "-wal").delete()
                File(stage.absolutePath + "-shm").delete()
                File(stage.absolutePath + "-journal").delete()
            }
        } finally {
            lastWriteCompletedAt = SystemClock.elapsedRealtime()
            saving.value = false
        }
    }
}
