package dev.sillyplayer.data

import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.sillyplayer.Graph
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class BackupState(
    val busy: Boolean = false,
    val stage: String = "",
    val progress: Float = -1f,
    val message: String = "",
    val omissions: List<String> = emptyList(),
)

class BackupRepository(private val g: Graph) {
    fun start(uri: String, restore: Boolean) {
        val request =
            androidx.work
                .OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(androidx.work.workDataOf("uri" to uri, "restore" to restore))
                .build()
        androidx.work.WorkManager.getInstance(g.context)
            .enqueueUniqueWork("sillyplayer-backup", androidx.work.ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() {
        androidx.work.WorkManager.getInstance(g.context).cancelUniqueWork("sillyplayer-backup")
    }

    val state = MutableStateFlow(BackupState())
    val approval = MutableStateFlow<Pair<Int, Long>?>(null)
    private var restoreDecision: CompletableDeferred<Boolean>? = null

    fun confirmRestore(accept: Boolean) {
        restoreDecision?.complete(accept)
    }

    suspend fun preview(): Pair<Int, List<String>> {
        val tracks = g.dao.tracks()
        val missing =
            tracks.filter {
                !it.available ||
                    runCatching { g.input(it.uri).use { input -> input.read() } }.isFailure
            }
        return (tracks.size - missing.size) to missing.map { it.title }
    }

    suspend fun export(uri: String) =
        g.fileGate.withLock {
            check(!state.value.busy)
            state.value = BackupState(true, "Checking library")
            try {
                val tracks = g.dao.tracks()
                val collections = g.dao.collections()
                val members = g.dao.members()
                val likes = g.dao.likes()
                val omitted = mutableListOf<String>()
                val rows = mutableListOf<JsonObject>()
                val covers = mutableListOf<JsonObject>()
                val assets = linkedMapOf<String, JsonObject>()
                var done = 0L
                val total = tracks.sumOf { it.size.coerceAtLeast(0) }
                g.output(uri).use { raw ->
                    ZipOutputStream(BufferedOutputStream(raw, 256 * 1024)).use { zip ->
                        suspend fun add(source: String, kind: String, extension: String): String {
                            val hash = g.input(source).use { it.sha256() }
                            val ext =
                                extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                                    ?: "bin"
                            val name = "$kind/$hash.$ext"
                            if (name in assets) return name
                            zip.putNextEntry(ZipEntry(name))
                            val digest = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            g.input(source).use { input ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    zip.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    count += n
                                    if (kind == "media") done += n
                                    state.value =
                                        BackupState(
                                            true,
                                            "Backing up audio",
                                            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f)
                                            else -1f,
                                        )
                                }
                            }
                            zip.closeEntry()
                            require(
                                digest.digest().joinToString("") { "%02x".format(it) } == hash
                            ) {
                                "A file changed during backup"
                            }
                            assets[name] = buildJsonObject {
                                put("path", name)
                                put("sha256", hash)
                                put("size", count)
                            }
                            return name
                        }
                        for (track in tracks) {
                            if (
                                !track.available ||
                                    runCatching { g.input(track.uri).use { it.read() } }.isFailure
                            ) {
                                omitted += track.title
                                continue
                            }
                            val fileName =
                                DocumentFile.fromSingleUri(g.context, Uri.parse(track.uri))?.name
                                    ?: Uri.parse(track.uri).lastPathSegment
                                    ?: "audio.mp3"
                            val media = add(track.uri, "media", fileName.substringAfterLast('.'))
                            val art =
                                if (track.art.isNotBlank())
                                    runCatching { add(track.art, "art", "img") }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault("")
                                else ""
                            val map = JsonCodec.encodeToJsonElement(track).jsonObject.toMutableMap()
                            for (key in
                                listOf(
                                    "uri",
                                    "available",
                                    "deleted",
                                    "managed",
                                    "modified",
                                    "hash",
                                    "size",
                                    "mergedInto",
                                    "root",
                                )) map.remove(key)
                            map["media"] = JsonPrimitive(media)
                            map["art"] = JsonPrimitive(art)
                            rows += JsonObject(map)
                        }
                        for (collection in collections) {
                            val art =
                                if (collection.art.isNotBlank())
                                    runCatching { add(collection.art, "art", "img") }
                                        .getOrDefault("")
                                else ""
                            covers += buildJsonObject {
                                put("id", collection.id)
                                put("name", collection.name)
                                put("kind", collection.kind)
                                put("art", art)
                            }
                        }
                        val present = rows.map { it.getValue("id").jsonPrimitive.content }.toSet()
                        val manifest = buildJsonObject {
                            put("format", "SillyPlayer Library")
                            put("version", 1)
                            put("libraryId", g.prefs.deviceId)
                            put("tracks", JsonArray(rows))
                            put("collections", JsonArray(covers))
                            put(
                                "members",
                                JsonCodec.encodeToJsonElement(
                                    members.filter { it.track in present }
                                ),
                            )
                            put(
                                "likes",
                                JsonCodec.encodeToJsonElement(likes.filter { it.track in present }),
                            )
                            put("assets", JsonArray(assets.values.toList()))
                        }
                        val data = manifest.toString().toByteArray()
                        require(data.size <= 16 * 1024 * 1024) { "Library manifest exceeds 16 MiB" }
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(data)
                        zip.closeEntry()
                    }
                }
                state.value =
                    BackupState(false, "Completed", 1f, "Backed up ${rows.size} songs", omitted)
            } catch (e: Exception) {
                runCatching { DocumentFile.fromSingleUri(g.context, Uri.parse(uri))?.delete() }
                state.value = BackupState(message = e.message ?: "Backup failed", stage = "Failed")
                throw e
            }
        }

    private data class InspectedArchive(val manifest: JsonObject, val names: Set<String>)

    private data class RestoreArchive(
        val origin: String,
        val rows: JsonArray,
        val collections: JsonArray,
        val members: JsonArray,
        val likes: JsonArray,
        val assets: Map<String, JsonObject>,
        val names: Set<String>,
        val total: Long,
    )

    private class RestorePlan(
        val archive: RestoreArchive,
        val restored: Map<Pair<String, String>, RestoreId>,
        val currentTracks: MutableMap<String, Track>,
        val currentHashes: MutableMap<String, Track>,
    ) {
        val trackMap = mutableMapOf<String, String>()
        val collectionMap = mutableMapOf<String, String>()
        val newTracks = mutableListOf<Track>()
        val newCollections = mutableListOf<Collection>()
        val repairedTracks = mutableListOf<Track>()
        val mapping = mutableListOf<RestoreId>()
        var reused = 0
        var repaired = 0
    }

    suspend fun restore(uri: String) = g.syncGate.withLock { restoreWithFileGate(uri) }

    private suspend fun restoreWithFileGate(uri: String) = g.fileGate.withLock {
        check(!state.value.busy)
        state.value = BackupState(true, "Reading backup")
        val stage = File(g.context.cacheDir, "restore-" + uuid()).also { it.mkdirs() }
        val published = mutableListOf<String>()
        val publication = File(g.context.filesDir, "restore-publish.json")
        var committed = false
        var ownsJournal = false
        try {
            check(!publication.exists()) {
                "Interrupted restore cleanup needs attention. Restore audio-folder access and restart SillyPlayer before restoring again."
            }
            ownsJournal = true
            val archive = validateRestoreArchive(inspectRestoreArchive(uri))
            extractRestoreArchive(uri, archive, stage)
            awaitRestoreApproval(archive)
            val plan = buildRestorePlan(archive, stage, published, publication)
            state.value = BackupState(true, "Saving restored library")
            commitRestorePlan(plan)
            committed = true
            state.value = BackupState(false, "Completed", 1f,
                "Restored ${plan.newTracks.size} songs · reused ${plan.reused} · repaired ${plan.repaired}")
        } catch (e: Exception) {
            if (!committed) withContext(NonCancellable) {
                for (value in published) runCatching {
                    DocumentFile.fromSingleUri(g.context, Uri.parse(value))?.delete()
                }
            }
            state.value = BackupState(message = e.message ?: "Restore failed", stage = "Failed")
            throw e
        } finally {
            approval.value = null
            restoreDecision = null
            withContext(NonCancellable) {
                if (ownsJournal && published.all {
                    g.dao.byUri(it) != null || DocumentFile.fromSingleUri(g.context, Uri.parse(it))?.exists() != true
                }) publication.delete()
                stage.deleteRecursively()
            }
        }
    }

    private suspend fun inspectRestoreArchive(uri: String): InspectedArchive {
        var manifest: JsonObject? = null
        val names = mutableSetOf<String>()
        var inspectedBytes = 0L
        val inspectionLimit = StatFs(g.context.cacheDir.path).availableBytes
        val pattern = Regex("(media|art)/[a-f0-9]{64}\\.[a-z0-9]{1,10}")
        g.input(uri).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    currentCoroutineContext().ensureActive()
                    require(names.add(entry.name) && names.size <= 100000) {
                        "Duplicate or excessive ZIP entries"
                    }
                    if (entry.name == "manifest.json") {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            require(out.size() <= 16 * 1024 * 1024) { "Manifest too large" }
                        }
                        manifest = JsonCodec.parseToJsonElement(out.toString("UTF-8")).jsonObject
                    } else {
                        require(pattern.matches(entry.name) && !entry.isDirectory) { "Unsafe ZIP entry" }
                        val discard = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = zip.read(discard)
                            if (n < 0) break
                            inspectedBytes += n
                            require(inspectedBytes <= inspectionLimit) { "Not enough storage to stage this archive" }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return InspectedArchive(manifest ?: error("Missing manifest.json"), names)
    }

    private fun restoreIds(values: JsonArray): Set<String> {
        val keys = values.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        require(keys.all { it.isNotBlank() && it.length <= 200 } && keys.distinct().size == keys.size) {
            "Invalid entity IDs"
        }
        return keys.toSet()
    }

    private fun validateRestoreArchive(inspected: InspectedArchive): RestoreArchive {
        val doc = inspected.manifest
        require(doc["format"]?.jsonPrimitive?.content == "SillyPlayer Library" &&
            doc["version"]?.jsonPrimitive?.int == 1) { "Unsupported backup format" }
        val origin = doc.getValue("libraryId").jsonPrimitive.content
        java.util.UUID.fromString(origin)
        val rows = doc.getValue("tracks").jsonArray
        val collections = doc.getValue("collections").jsonArray
        val members = doc.getValue("members").jsonArray
        val likes = doc.getValue("likes").jsonArray
        val trackIds = restoreIds(rows)
        val collectionIds = restoreIds(collections)
        restoreIds(members)
        for (m in members) {
            val value = m.jsonObject
            require(value.getValue("track").jsonPrimitive.content in trackIds &&
                value.getValue("collection").jsonPrimitive.content in collectionIds) { "Broken collection reference" }
        }
        for (like in likes) require(like.jsonObject.getValue("track").jsonPrimitive.content in trackIds) {
            "Broken liked-song reference"
        }
        val pattern = Regex("(media|art)/[a-f0-9]{64}\\.[a-z0-9]{1,10}")
        val assets = doc.getValue("assets").jsonArray.associate {
            val asset = it.jsonObject
            val name = asset.getValue("path").jsonPrimitive.content
            require(pattern.matches(name) && name.substringAfter('/').substringBefore('.') ==
                asset.getValue("sha256").jsonPrimitive.content) { "Invalid asset" }
            name to asset
        }
        require(assets.size == doc.getValue("assets").jsonArray.size &&
            inspected.names == assets.keys + "manifest.json") { "Missing or undeclared archive payload" }
        val total = assets.values.fold(0L) { sum, asset ->
            val size = asset.getValue("size").jsonPrimitive.long
            require(size >= 0 && size < Long.MAX_VALUE - sum) { "Invalid archive size" }
            sum + size
        }
        require(total < StatFs(g.context.cacheDir.path).availableBytes - 1024 * 1024) {
            "Not enough space to stage this backup"
        }
        for (v in rows) {
            val track = v.jsonObject
            val speed = track["speed"]?.jsonPrimitive?.double ?: 1.0
            val volume = track["volumePercent"]?.jsonPrimitive?.double ?: 100.0
            require(speed.isFinite() && speed in .5..2.0 && volume.isFinite() && volume in 0.0..300.0) {
                "Invalid song modifiers"
            }
            val media = track.getValue("media").jsonPrimitive.content
            require(media.startsWith("media/") && media in assets) { "Missing song audio" }
            val art = track["art"]?.jsonPrimitive?.content ?: ""
            require(art.isEmpty() || (art.startsWith("art/") && art in assets)) { "Missing song artwork" }
        }
        for (v in collections) {
            val collection = v.jsonObject
            require(collection.getValue("kind").jsonPrimitive.content in listOf("playlist", "custom")) {
                "Invalid collection kind"
            }
            val art = collection["art"]?.jsonPrimitive?.content ?: ""
            require(art.isEmpty() || (art.startsWith("art/") && art in assets)) { "Missing collection artwork" }
        }
        return RestoreArchive(origin, rows, collections, members, likes, assets, inspected.names, total)
    }

    private suspend fun extractRestoreArchive(uri: String, archive: RestoreArchive, stage: File) {
        var done = 0L
        val extracted = mutableSetOf<String>()
        g.input(uri).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(extracted.add(entry.name)) { "Duplicate ZIP entry during restore" }
                    if (entry.name == "manifest.json") {
                        zip.closeEntry()
                        continue
                    }
                    val asset = archive.assets[entry.name] ?: error("Archive changed during restore")
                    val expected = asset.getValue("size").jsonPrimitive.long
                    val target = File(stage, entry.name)
                    target.parentFile?.mkdirs()
                    val hash = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = zip.read(buffer)
                            if (n < 0) break
                            written += n
                            require(written <= expected) { "Archive payload exceeds declared size" }
                            out.write(buffer, 0, n)
                            hash.update(buffer, 0, n)
                            done += n
                            state.value = BackupState(true, "Verifying backup",
                                if (archive.total > 0) done.toFloat() / archive.total else -1f)
                        }
                    }
                    require(written == expected && hash.digest().joinToString("") { "%02x".format(it) } ==
                        asset.getValue("sha256").jsonPrimitive.content) { "Backup checksum mismatch" }
                    zip.closeEntry()
                }
            }
        }
        require(extracted == archive.names) { "Archive changed during restore" }
    }

    private suspend fun awaitRestoreApproval(archive: RestoreArchive) {
        val decision = CompletableDeferred<Boolean>()
        restoreDecision = decision
        approval.value = archive.rows.size to archive.total
        require(decision.await()) { "Restore cancelled" }
        approval.value = null
        require(g.prefs.state.value.musicDir.isNotBlank()) { "Choose a restore destination in Settings" }
    }

    private fun restoreCover(stage: File, asset: String): String {
        if (asset.isBlank()) return ""
        val file = File(g.context.filesDir, "art/" + File(asset).name).also { it.parentFile?.mkdirs() }
        if (!file.exists()) File(stage, asset).copyTo(file)
        return file.toURI().toString()
    }

    private suspend fun publishRestoredAudio(source: File, title: String, hash: String,
        published: MutableList<String>, publication: File): String =
        g.library.publish(source, safeName(title) + " [${hash.take(8)}]." + source.extension, "audio/*",
            created = { value ->
                published += value
                appendRestorePublication(publication, value)
            })

    private suspend fun buildRestorePlan(archive: RestoreArchive, stage: File,
        published: MutableList<String>, publication: File): RestorePlan {
        val restored = g.dao.restoredForOrigin(archive.origin).associateBy { it.kind to it.remote }
        val currentTracks = g.dao.recoveryTracks().filter { it.mergedInto.isBlank() || it.isRemovalTombstone() }.associateBy { it.id }.toMutableMap()
        val hashes = currentTracks.values.filter { !it.deleted && it.hash.isNotBlank() }.associateBy { it.hash }.toMutableMap()
        val plan = RestorePlan(archive, restored, currentTracks, hashes)
        buildRestoredTracks(plan, stage, published, publication)
        buildRestoredCollections(plan, stage)
        return plan
    }

    private suspend fun buildRestoredTracks(plan: RestorePlan, stage: File,
        published: MutableList<String>, publication: File) {
        val origin = plan.archive.origin
        for (v in plan.archive.rows) {
            val fields = v.jsonObject
            val remote = fields.getValue("id").jsonPrimitive.content
            val asset = fields.getValue("media").jsonPrimitive.content
            val hash = File(asset).nameWithoutExtension
            currentCoroutineContext().ensureActive()
            val mapped = plan.restored["track" to remote]?.local
            val existing = mapped?.let { plan.currentTracks[it] }
                ?: plan.currentHashes[hash]
                ?: if (origin == g.prefs.deviceId) plan.currentTracks[remote] else null
            if (existing != null) {
                repairRestoredTrack(plan, existing, File(stage, asset), hash, published, publication)
                plan.trackMap[remote] = existing.id
                plan.reused++
            } else {
                val id = if (remote !in plan.currentTracks) remote else uuid()
                val source = File(stage, asset)
                val title = fields["title"]?.jsonPrimitive?.content ?: "Audio"
                val target = publishRestoredAudio(source, title, hash, published, publication)
                val decoded = decodeNewRestoredTrack(fields, id, target, source, hash, stage)
                plan.newTracks += decoded
                plan.currentTracks[id] = decoded
                plan.currentHashes[hash] = decoded
                plan.trackMap[remote] = id
            }
            plan.mapping += RestoreId(origin, "track", remote, plan.trackMap.getValue(remote))
        }
    }

    private suspend fun repairRestoredTrack(plan: RestorePlan, existing: Track, source: File, hash: String,
        published: MutableList<String>, publication: File) {
        val readable = runCatching { g.input(existing.uri).use { it.read() >= 0 } }.getOrDefault(false)
        val matchesRemovedAudio = !existing.deleted || (readable &&
            runCatching { g.input(existing.uri).use { it.sha256() } == hash }.getOrDefault(false))
        val repaired = if (!readable || !matchesRemovedAudio) {
            val target = publishRestoredAudio(source, existing.title, hash, published, publication)
            val document = DocumentFile.fromSingleUri(g.context, Uri.parse(target))
            existing.copy(uri = target, hash = hash, size = source.length(), available = true,
                managed = true, root = "", deleted = false, mergedInto = "", modified = document?.lastModified() ?: 0,
                fileName = document?.name ?: source.name)
        } else if (!existing.available || existing.deleted) existing.copy(available = true, deleted = false, mergedInto = "") else return
        plan.repairedTracks += repaired
        plan.currentTracks[existing.id] = repaired
        plan.currentHashes[hash] = repaired
        plan.repaired++
    }

    private fun decodeNewRestoredTrack(fields: JsonObject, id: String, target: String,
        source: File, hash: String, stage: File): Track {
        val normalized = fields.toMutableMap()
        normalized["nightcore"] = JsonPrimitive(fields["nightcore"]?.jsonPrimitive?.booleanOrNull
            ?: (fields["nightcore"]?.jsonPrimitive?.intOrNull == 1))
        normalized["id"] = JsonPrimitive(id)
        normalized["uri"] = JsonPrimitive(target)
        normalized["art"] = JsonPrimitive(restoreCover(stage, fields["art"]?.jsonPrimitive?.content ?: ""))
        normalized["hash"] = JsonPrimitive(hash)
        normalized["size"] = JsonPrimitive(source.length())
        normalized["available"] = JsonPrimitive(true)
        normalized["deleted"] = JsonPrimitive(false)
        normalized["managed"] = JsonPrimitive(true)
        return decodeBackupTrack(JsonObject(normalized))
    }

    private suspend fun buildRestoredCollections(plan: RestorePlan, stage: File) {
        val origin = plan.archive.origin
        for (v in plan.archive.collections) {
            val collection = v.jsonObject
            val remote = collection.getValue("id").jsonPrimitive.content
            val mapped = plan.restored["collection" to remote]?.local
            val current = mapped?.let { g.dao.collection(it) }
                ?: if (origin == g.prefs.deviceId) g.dao.collection(remote) else null
            val id = current?.id ?: if (g.dao.collection(remote) == null) remote else uuid()
            plan.collectionMap[remote] = id
            if (current == null) plan.newCollections += Collection(id,
                collection.getValue("name").jsonPrimitive.content,
                collection.getValue("kind").jsonPrimitive.content,
                restoreCover(stage, collection["art"]?.jsonPrimitive?.content ?: ""))
            plan.mapping += RestoreId(origin, "collection", remote, id)
        }
    }

    private suspend fun commitRestorePlan(plan: RestorePlan) = g.db.withTransaction {
        val memberIndex = g.dao.members().associateBy { it.id }.toMutableMap()
        val nextPositions = memberIndex.values.groupBy { it.collection }.mapValues { (_, entries) ->
            (entries.maxOfOrNull { it.position } ?: -1) + 1
        }.toMutableMap()
        val liked = g.dao.likes().map { it.track }.toMutableSet()
        for (track in plan.repairedTracks) g.library.saveTrack(track)
        for (track in plan.newTracks) g.library.saveTrack(track)
        for (collection in plan.newCollections)
            g.library.collection(collection.name, collection.kind, collection.id, collection.art)
        for (v in plan.archive.members) {
            val member = v.jsonObject
            val remote = member.getValue("id").jsonPrimitive.content
            val collection = plan.collectionMap.getValue(member.getValue("collection").jsonPrimitive.content)
            val track = plan.trackMap.getValue(member.getValue("track").jsonPrimitive.content)
            currentCoroutineContext().ensureActive()
            val mapped = plan.restored["member" to remote]?.local
            val existing = memberIndex[mapped ?: if (plan.archive.origin == g.prefs.deviceId) remote else ""]
            if (existing == null) {
                val id = if (remote in memberIndex) uuid() else remote
                val value = Member(id, collection, track, nextPositions[collection] ?: 0)
                g.dao.putMember(value)
                memberIndex[id] = value
                nextPositions[collection] = value.position + 1
                g.library.journal("members", id, JsonCodec.encodeToJsonElement(value))
                plan.mapping += RestoreId(plan.archive.origin, "member", remote, id)
            }
        }
        for (v in plan.archive.likes) {
            val track = plan.trackMap.getValue(v.jsonObject.getValue("track").jsonPrimitive.content)
            if (liked.add(track)) g.library.like(track)
        }
        for (collection in plan.collectionMap.values.distinct()) g.library.order(collection)
        for (mapping in plan.mapping) g.dao.putRestored(mapping)
    }
}
