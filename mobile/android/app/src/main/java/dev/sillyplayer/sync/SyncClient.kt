package dev.sillyplayer.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.*
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import dev.sillyplayer.graph
import dev.sillyplayer.online.workNotification
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class SyncState(
    val busy: Boolean = false,
    val stage: String = "",
    val progress: Float = -1f,
    val message: String = "",
    val transferred: Long = 0,
)

data class SyncResolutionSummary(
    val resolved: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val cancelled: Boolean = false,
    val message: String = "",
)

data class DiscoveredDesktop(
    val deviceId: String,
    val name: String,
    val host: String,
    val fingerprint: String,
)

internal const val SYNC_DISCOVERY_PORT = 43818

internal fun parseDiscoveryReply(raw: String, senderIp: String): DiscoveredDesktop? {
    val obj = runCatching { JsonCodec.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    if (obj["service"]?.jsonPrimitive?.contentOrNull != "sillyplayer-sync") return null
    if (obj["v"]?.jsonPrimitive?.intOrNull != 1) return null
    val deviceId = obj["deviceId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "Desktop" }
    val port = obj["port"]?.jsonPrimitive?.intOrNull ?: return null
    val fp = obj["fp"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (deviceId.isEmpty() || port !in 1..65535 || !fp.matches(Regex("[a-f0-9]{64}"))) return null
    val cleanIp = senderIp.removePrefix("/").trim()
    if (!cleanIp.matches(Regex("[0-9.]+"))) return null
    return DiscoveredDesktop(deviceId, name, "$cleanIp:$port", fp)
}

internal fun snapshotSongChoices(snapshot: List<SyncConflict>): List<SyncConflict> {
    val peer = snapshot.firstOrNull { it.entity == "tracks" }?.peer ?: return emptyList()
    return snapshot.filter { it.entity == "tracks" && it.peer == peer }.distinctBy { it.id }
}

private class SyncReviewRequired : RuntimeException()

class SyncClient(private val g: Graph) {
    val state = MutableStateFlow(SyncState())
    val resolutionSummary = MutableStateFlow(SyncResolutionSummary())
    @Volatile private var resolutionJob: Job? = null
    val connectedPeer = MutableStateFlow<String?>(null)
    private val acceptedChanges = mutableSetOf<Pair<String, String>>()
    private val pendingMediaSyncPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val pendingLink = MutableStateFlow("")
    val peers = g.dao.observePeers().stateIn(g.scope, SharingStarted.Eagerly, emptyList())
    val conflicts = g.dao.observeConflicts().stateIn(g.scope, SharingStarted.Eagerly, emptyList())

    private fun secretKey(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("sillyplayer-pairing", null) as? javax.crypto.SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "sillyplayer-pairing",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    private fun protect(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun reveal(value: String): String {
        val parts = value.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    private fun client(fingerprint: String): OkHttpClient =
        clients.computeIfAbsent(fingerprint) { createClient(it) }

    private fun createClient(fingerprint: String): OkHttpClient {
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid certificate fingerprint" }
        val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) {}

                override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                    require(
                        chain.isNotEmpty() &&
                            MessageDigest.isEqual(
                                MessageDigest.getInstance("SHA-256").digest(chain[0].encoded),
                                expected,
                            )
                    ) {
                        "Desktop certificate changed; pair again"
                    }
                    chain[0].checkValidity()
                }
            }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, session ->
                val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                certificate != null &&
                    MessageDigest.isEqual(
                        MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
                        expected,
                    )
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    suspend fun pair(link: String): Peer {
        val u = Uri.parse(link.trim())
        require(u.scheme == "sillyplayer" && u.host == "pair" && u.getQueryParameter("v") == "1") {
            "Paste a SillyPlayer pairing link"
        }
        val host = u.getQueryParameter("host") ?: error("Missing desktop address")
        require(host.matches(Regex("[0-9.]+:[0-9]{1,5}"))) { "Invalid desktop address" }
        val fingerprint = u.getQueryParameter("fp") ?: error("Missing certificate fingerprint")
        val token = u.getQueryParameter("token") ?: error("Missing pairing token")
        state.value = SyncState(true, "Pairing desktop")
        try {
            val previous = g.dao.peers().firstOrNull { it.fingerprint == fingerprint }
            val body = buildJsonObject {
                if (g.prefs.deviceKey.isNotEmpty()) put("deviceKey", g.prefs.deviceKey)
                previous?.let { put("previousCredential", reveal(it.credential)) }
                put("deviceId", g.prefs.deviceId)
                put("name", Build.MANUFACTURER + " " + Build.MODEL)
                put("token", token)
            }
            // pairing tokens are single-use: never automatically replay a successful pair
            val response = syncResponse(client(fingerprint),
                Request.Builder().url("https://$host/sync/v1/pair")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
            ) { syncJson(it) }
            val peer =
                Peer(
                    response.getValue("deviceId").jsonPrimitive.content,
                    response.getValue("name").jsonPrimitive.content,
                    host,
                    fingerprint,
                    protect(response.getValue("credential").jsonPrimitive.content),
                )
            g.dao.putPeer(peer)
            seed(peer)
            pendingLink.value = ""
            state.value = SyncState(message = "Paired with ${peer.name} · choose music to copy")
            return peer
        } catch (e: Exception) {
            state.value = SyncState(message = e.message ?: "Pairing failed")
            throw e
        }
    }

    private suspend fun seed(peer: Peer) =
        g.db.withTransaction {
            g.dao.adoptUnpairedOutbox(peer.id)
            for (t in g.dao.tracks()) if (g.dao.replica(peer.id, "tracks", t.id) == null)
                g.dao.putOutbox(
                    Outbox(
                        peer = peer.id,
                        entity = "tracks",
                        key = t.id,
                        base = 0,
                        payload = g.library.trackWire(t).toString(),
                    )
                )
            for (c in g.dao.collections()) g.dao.putOutbox(
                Outbox(
                    peer = peer.id,
                    entity = "collections",
                    key = c.id,
                    base = g.dao.replica(peer.id, "collections", c.id)?.revision ?: 0,
                    payload = collectionWire(c).toString(),
                )
            )
            for (m in g.dao.members()) g.dao.putOutbox(
                Outbox(
                    peer = peer.id,
                    entity = "members",
                    key = m.id,
                    base = g.dao.replica(peer.id, "members", m.id)?.revision ?: 0,
                    payload = JsonCodec.encodeToJsonElement(m).toString(),
                )
            )
            for (c in g.dao.collections()) if (g.dao.pending(peer.id, "orders", c.id) == null)
                g.dao.putOutbox(
                    Outbox(
                        peer = peer.id,
                        entity = "orders",
                        key = c.id,
                        base = g.dao.replica(peer.id, "orders", c.id)?.revision ?: 0,
                        payload =
                            buildJsonObject {
                                    put("id", c.id)
                                    put(
                                        "entries",
                                        JsonArray(g.dao.members(c.id).map { JsonPrimitive(it.id) }),
                                    )
                                }
                                .toString(),
                    )
                )
            for (l in g.dao.likes()) g.dao.putOutbox(
                Outbox(
                    peer = peer.id,
                    entity = "likes",
                    key = l.track,
                    base = 0,
                    payload = JsonCodec.encodeToJsonElement(l).toString(),
                )
            )
            for (c in g.dao.counters().filter { it.device == g.prefs.deviceId }) g.dao.putOutbox(
                Outbox(
                    peer = peer.id,
                    entity = "counters",
                    key = c.device + ":" + c.component,
                    base = 0,
                    payload = JsonCodec.encodeToJsonElement(c).toString(),
                )
            )
            for (p in g.dao.plays()) g.dao.putOutbox(
                Outbox(
                    peer = peer.id,
                    entity = "plays",
                    key = p.session,
                    base = 0,
                    payload = JsonCodec.encodeToJsonElement(p).toString(),
                )
            )
        }

    private suspend fun collectionWire(c: dev.sillyplayer.data.Collection): JsonObject {
        val hash =
            if (c.art.isNotBlank())
                runCatching { g.input(c.art).use { it.sha256() } }.getOrDefault(c.artHash)
            else c.artHash
        return buildJsonObject {
            put("id", c.id)
            put("name", c.name)
            put("kind", c.kind)
            put("artHash", hash)
        }
    }

    suspend fun scope(peer: Peer, all: Boolean, selection: Set<String>) {
        g.dao.putPeer(
            (g.dao.peers().firstOrNull { it.id == peer.id } ?: error("Desktop is no longer paired"))
                .copy(
                    allMedia = all,
                    selected = JsonArray(selection.map { JsonPrimitive(it) }).toString(),
                )
        )
    }

    fun start(peer: Peer) {
        check(!g.resetting.value) { "Library reset is in progress" }
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("peer" to peer.id))
                .build()
        WorkManager.getInstance(g.context)
            .enqueueUniqueWork("sillyplayer-sync", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() {
        connectedPeer.value = null
        resolutionJob?.cancel(CancellationException("Song choices cancelled"))
        WorkManager.getInstance(g.context).cancelUniqueWork("sillyplayer-sync")
    }

    suspend fun unpair(peer: Peer) {
        if (connectedPeer.value == peer.id) connectedPeer.value = null
        g.db.withTransaction {
            g.dao.deletePeer(peer.id)
            g.dao.clearPeerOutbox(peer.id)
            g.dao.clearPeerReplicas(peer.id)
            g.dao.clearPeerConflicts(peer.id)
        }
    }

    suspend fun discoverDesktops(timeoutMs: Long = 1600): List<DiscoveredDesktop> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, DiscoveredDesktop>()
            val probe = """{"discover":"sillyplayer-sync","v":1}""".toByteArray(Charsets.UTF_8)
            runCatching {
                java.net.DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 350
                    val targets = LinkedHashSet<java.net.InetAddress>()
                    runCatching { targets += java.net.InetAddress.getByName("255.255.255.255") }
                    runCatching {
                        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                        while (ifaces != null && ifaces.hasMoreElements()) {
                            val iface = ifaces.nextElement()
                            if (!iface.isUp || iface.isLoopback) continue
                            for (addr in iface.interfaceAddresses) {
                                addr.broadcast?.let { targets += it }
                            }
                        }
                    }
                    for (addr in targets) {
                        runCatching {
                            socket.send(
                                java.net.DatagramPacket(probe, probe.size, addr, SYNC_DISCOVERY_PORT)
                            )
                        }
                    }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(2048)
                    while (System.currentTimeMillis() < deadline) {
                        currentCoroutineContext().ensureActive()
                        val packet = java.net.DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                            val ip = packet.address?.hostAddress.orEmpty()
                            val desktop = parseDiscoveryReply(text, ip)
                            if (desktop != null) found[desktop.deviceId] = desktop
                        } catch (_: java.net.SocketTimeoutException) {
                            if (found.isNotEmpty()) break
                        }
                    }
                }
            }
            found.values.toList()
        }

    suspend fun refreshPeerHost(peer: Peer): Peer? {
        val match =
            discoverDesktops().firstOrNull {
                it.deviceId == peer.id && it.fingerprint == peer.fingerprint
            } ?: return null
        if (match.host == peer.host) return peer
        val updated = peer.copy(host = match.host, name = match.name.ifBlank { peer.name })
        g.dao.putPeer(updated)
        return updated
    }

    suspend fun rediscover(peer: Peer): String {
        val updated = refreshPeerHost(peer)
            ?: return "Could not find ${peer.name} on this Wi-Fi network. Make sure Phone sync is enabled on desktop."
        return if (updated.host != peer.host)
            "Updated ${updated.name} address to ${updated.host}"
        else "${updated.name} is reachable at ${updated.host}"
    }

    private fun request(
        peer: Peer,
        method: String,
        path: String,
        data: JsonElement? = null,
    ): Request {
        val body =
            if (method in listOf("POST", "PUT"))
                (data ?: buildJsonObject {})
                    .toString()
                    .toRequestBody("application/json".toMediaType())
            else null
        return Request.Builder()
            .url("https://${peer.host}/sync/v1$path")
            .header("Authorization", "Bearer " + reveal(peer.credential))
            .method(method, body)
            .build()
    }

    private suspend fun exchange(
        peer: Peer,
        client: OkHttpClient,
        method: String,
        path: String,
        data: JsonElement? = null,
    ): JsonObject {
        val preparationStarted = System.nanoTime()
        var preparationAttempt = 0
        while (true) {
            val result = retrySyncNetwork(onRetry = { retry ->
                state.value = state.value.copy(stage = "Connection interrupted · retry $retry/5")
            }) {
                syncResponse(client, request(peer, method, path, data)) { response ->
                    if (response.code == 503) null else syncJson(response)
                }
            }
            if (result != null) return result
            check(System.nanoTime() - preparationStarted < TimeUnit.MINUTES.toNanos(30)) {
                "Desktop library preparation timed out; tap Sync to continue"
            }
            state.value = state.value.copy(stage = "Preparing desktop library")
            delay(minOf(15_000L, 1_000L shl minOf(preparationAttempt++, 4)))
        }
    }

    suspend fun synchronize(id: String) = g.syncGate.withLock { synchronizeUnlocked(id) }

    private suspend fun synchronizeUnlocked(id: String) {
        g.prefs.loaded.await()
        g.library.ready.await()
        check(!g.resetting.value) { "Library reset is in progress" }
        var peer = g.dao.peers().firstOrNull { it.id == id } ?: error("Desktop is no longer paired")
        val http = client(peer.fingerprint)
        state.value = SyncState(true, "Preparing changes")
        try {
            // receive desktop changes before sending phone edits, so a stale phone record cannot overwrite a desktop deletion before the user gets to review it
            var cursor = peer.cursor
            try {
                fetchConflicts(peer, http)
            } catch (e: java.io.IOException) {
                state.value = state.value.copy(stage = "Searching for ${peer.name} on Wi-Fi…")
                val rediscovered = refreshPeerHost(peer)
                if (rediscovered != null && rediscovered.host != peer.host) {
                    peer = rediscovered
                    state.value = state.value.copy(stage = "Found ${peer.name} at ${peer.host}")
                    fetchConflicts(peer, http)
                } else {
                    throw e
                }
            }
            connectedPeer.value = peer.id
            cursor = receiveChanges(peer, http, cursor)
            // parents precede memberships, regardless of edit timestamps
            while (true) {
                val batch = nextSyncBatch(g.dao.syncBatch(peer.id))
                val pending = batch.entries
                if (pending.isEmpty()) break
                state.value = state.value.copy(stage = "Sending library changes")
                val response =
                    exchange(
                        peer,
                        http,
                        "POST",
                        "/changes",
                        batch.body,
                    )
                val aliases = response["aliases"]?.jsonArray ?: JsonArray(emptyList())
                for (v in aliases) {
                    val a = v.jsonObject
                    remap(
                        a.getValue("entity").jsonPrimitive.content,
                        a.getValue("remote").jsonPrimitive.content,
                        a.getValue("canonical").jsonPrimitive.content,
                    )
                }
                val results = response["results"] as? JsonArray
                    ?: error("Desktop returned no sync results; pending changes are kept")
                val pendingById = pending.associateBy { it.opId }
                require(results.isNotEmpty()) { "Desktop acknowledged no changes; pending changes are kept" }
                val acknowledged = mutableSetOf<String>()
                val updates = mutableMapOf<String, Track>()
                val removals = mutableSetOf<String>()
                g.db.withTransaction {
                for (v in results) {
                    val r = v.jsonObject
                    val op = r.getValue("opId").jsonPrimitive.content
                    require(acknowledged.add(op)) { "Desktop acknowledged the same change twice" }
                    val sent = pendingById[op] ?: error("Desktop acknowledged an unknown change")
                    sent.let {
                            val canonical = r["key"]?.jsonPrimitive?.content
                            if (
                                canonical != null &&
                                    canonical != sent.key &&
                                    sent.entity in listOf("tracks", "collections")
                            )
                                remap(sent.entity, sent.key, canonical)
                        }
                    when (r.getValue("status").jsonPrimitive.content) {
                        "error" ->
                            error(r["error"]?.jsonPrimitive?.content ?: "A sync change failed")
                        "conflict" -> {
                            g.dao.putConflict(
                                SyncConflict(
                                    r.getValue("conflict").jsonPrimitive.content,
                                    r.getValue("entity").jsonPrimitive.content,
                                    r.getValue("key").jsonPrimitive.content,
                                    r["local"]?.toString(),
                                    r["remote"]?.toString(),
                                    r.getValue("revision").jsonPrimitive.long,
                                    r["fields"]?.toString() ?: "[]",
                                    peer.id,
                                )
                            )
                            g.dao.deleteOutbox(op)
                        }
                        "applied" -> g.db.withTransaction {
                            apply(
                                peer,
                                r.getValue("entity").jsonPrimitive.content,
                                r.getValue("key").jsonPrimitive.content,
                                r["payload"],
                                r.getValue("revision").jsonPrimitive.long,
                                reviewDesktop = false,
                                updates = updates, removals = removals,
                            )
                            g.dao.deleteOutbox(op)
                        }
                        else -> error("Desktop returned an unknown sync outcome; pending changes are kept")
                    }
                }
                }
                publishTrackChanges(updates, removals)
            }
            fetchConflicts(peer, http)
            cursor = receiveChanges(peer, http, cursor)
            val blockedTracks = g.dao.conflictsForPeer(peer.id).filter { it.entity == "tracks" }.map { it.key }.toSet()
            val selected =
                JsonCodec.parseToJsonElement(peer.selected)
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            val tracks = selectedSyncTracks(peer, g.dao.tracks(), g.dao.members()).filter { it.id !in blockedTracks }
            val selectedCollections =
                g.dao.collections().filter {
                    it.artHash.isNotBlank() && (peer.allMedia || "collection:" + it.id in selected)
                }
            state.value = state.value.copy(stage = "Checking synced media…", progress = -1f)
            val hashRegex = Regex("[a-f0-9]{64}")
            val candidateHashes = LinkedHashSet<String>(tracks.size * 2 + selectedCollections.size)
            for (t in tracks) {
                if (t.hash.matches(hashRegex)) candidateHashes += t.hash
                if (t.artHash.matches(hashRegex)) candidateHashes += t.artHash
            }
            for (c in selectedCollections) {
                if (c.artHash.matches(hashRegex)) candidateHashes += c.artHash
            }
            val missingOnDesktop = checkMissingBlobs(peer, http, candidateHashes)
            val copied = java.util.concurrent.atomic.AtomicInteger(0)
            val missingAudio = java.util.concurrent.atomic.AtomicInteger(0)
            val missingSamples = java.util.Collections.synchronizedList(mutableListOf<String>())
            val failures = java.util.concurrent.atomic.AtomicInteger(0)
            val failureSamples = java.util.Collections.synchronizedList(mutableListOf<String>())
            fun recordFailure(message: String) {
                failures.incrementAndGet()
                synchronized(failureSamples) {
                    if (failureSamples.size < 3) failureSamples += message.take(500)
                }
            }
            val bytes = java.util.concurrent.atomic.AtomicLong(0L)
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val transferLimit = kotlinx.coroutines.sync.Semaphore(3)
            coroutineScope {
                tracks.map { t ->
                    async(Dispatchers.IO) {
                        transferLimit.acquire()
                        try {
                            currentCoroutineContext().ensureActive()
                            val needsAudioCheck =
                                t.hash.matches(hashRegex) &&
                                    (!t.available || t.uri.isBlank() || missingOnDesktop == null || t.hash in missingOnDesktop)
                            val needsArtCheck =
                                t.artHash.isNotBlank() &&
                                    (t.art.isBlank() || missingOnDesktop == null || t.artHash in missingOnDesktop)
                            try {
                                if (t.hash.matches(hashRegex)) {
                                    val readable =
                                        t.uri.isNotBlank() &&
                                            runCatching { g.input(t.uri).use { it.read() >= 0 } }
                                                .getOrDefault(false)
                                    val verifiedLocal = readable && (t.available ||
                                        runCatching { g.input(t.uri).use { it.sha256() } == t.hash }.getOrDefault(false))
                                    if (verifiedLocal) {
                                        if (!t.available) {
                                            val restored = (g.dao.track(t.id) ?: t).copy(available = true)
                                            g.dao.putTrack(restored)
                                            withContext(Dispatchers.Main) { g.engine.value?.updateTrack(restored) }
                                        }
                                        if (missingOnDesktop == null || t.hash in missingOnDesktop) {
                                            bytes.addAndGet(upload(peer, http, t.hash, t.uri))
                                        }
                                    } else {
                                        val file = download(peer, http, t.hash)
                                        val wire =
                                            g.dao.replica(peer.id, "tracks", t.id)?.payload?.let {
                                                JsonCodec.parseToJsonElement(it).jsonObject
                                            }
                                        val filename =
                                            wire?.get("fileName")?.jsonPrimitive?.content
                                                ?: safeName(t.title) + ".mp3"
                                        g.fileGate.withLock {
                                            val uri =
                                                if (readable) {
                                                    g.library.replaceContentUnlocked(t.uri, file)
                                                    t.uri
                                                } else
                                                    g.library.publish(file, filename, syncAudioMime(filename))
                                            val updated =
                                                (g.dao.track(t.id) ?: t).copy(
                                                    uri = uri,
                                                    available = true,
                                                    deleted = false,
                                                    managed = true,
                                                    size = file.length(),
                                                )
                                            g.dao.putTrack(updated)
                                            withContext(Dispatchers.Main) {
                                                g.engine.value?.updateTrack(updated)
                                            }
                                            uri
                                        }
                                        copied.incrementAndGet()
                                        bytes.addAndGet(file.length())
                                        file.delete()
                                    }
                                } else if (!t.available || t.uri.isBlank()) {
                                    missingAudio.incrementAndGet()
                                    synchronized(missingSamples) {
                                        if (missingSamples.size < 3) missingSamples += t.title
                                    }
                                }
                                if (t.artHash.isNotBlank()) {
                                    val local =
                                        t.art.takeIf {
                                            it.isNotBlank() &&
                                                runCatching { g.input(it).use { it.read() >= 0 } }
                                                    .getOrDefault(false)
                                        }
                                    if (local != null) {
                                        if (missingOnDesktop == null || t.artHash in missingOnDesktop) {
                                            bytes.addAndGet(upload(peer, http, t.artHash, local))
                                        }
                                    } else {
                                        val file = download(peer, http, t.artHash)
                                        val target =
                                            publishSyncBlob(
                                                file,
                                                File(g.context.filesDir, "art/sync"),
                                                t.artHash,
                                                "img",
                                            )
                                        val updated =
                                            (g.dao.track(t.id) ?: t).copy(art = target.toURI().toString())
                                        g.dao.putTrack(updated)
                                        withContext(Dispatchers.Main) { g.engine.value?.updateTrack(updated) }
                                        file.delete()
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: SyncPausedException) {
                                throw e
                            } catch (e: Exception) {
                                recordFailure("${t.title}: ${e.message ?: "Media transfer failed"}")
                            }
                            val doneCount = completed.incrementAndGet()
                            if (needsAudioCheck || needsArtCheck || doneCount == 1 || doneCount % 25 == 0 || doneCount == tracks.size) {
                                state.value =
                                    state.value.copy(
                                        stage = "Syncing audio $doneCount/${tracks.size}: ${t.title}",
                                        progress = if (tracks.isNotEmpty()) doneCount.toFloat() / tracks.size else 1f,
                                    )
                            }
                        } finally {
                            transferLimit.release()
                        }
                    }
                }.awaitAll()
            }
            coroutineScope {
                selectedCollections.map { c ->
                    async(Dispatchers.IO) {
                        transferLimit.acquire()
                        try {
                            if (
                                c.art.isNotBlank() &&
                                    runCatching { g.input(c.art).use { it.read() >= 0 } }
                                        .getOrDefault(false)
                            ) {
                                if (missingOnDesktop == null || c.artHash in missingOnDesktop) {
                                    upload(peer, http, c.artHash, c.art)
                                }
                            } else {
                                val file = download(peer, http, c.artHash)
                                val target =
                                    publishSyncBlob(
                                        file,
                                        File(g.context.filesDir, "art/sync"),
                                        c.artHash,
                                        "img",
                                    )
                                g.dao.putCollection(c.copy(art = target.toURI().toString()))
                                file.delete()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: SyncPausedException) {
                            throw e
                        } catch (e: Exception) {
                            recordFailure("${c.name} artwork: ${e.message}")
                        } finally {
                            transferLimit.release()
                        }
                    }
                }.awaitAll()
            }
            if (missingAudio.get() > 0)
                recordFailure("${missingAudio.get()} songs have no transferable desktop audio: ${missingSamples.joinToString()}")
            require(failures.get() == 0) {
                "${failures.get()} media transfers need retry. ${failureSamples.joinToString("; ")}"
            }
            exchange(peer, http, "POST", "/ack", buildJsonObject { put("cursor", cursor) })
            g.dao.putPeer(
                (g.dao.peers().firstOrNull { it.id == peer.id } ?: peer).copy(cursor = cursor)
            )
            pendingMediaSyncPeers.remove(peer.id)
            connectedPeer.value = peer.id
            val reviews = g.dao.conflictsForPeer(peer.id).size
            state.value =
                SyncState(
                    false,
                    "Completed",
                    1f,
                    "Sync complete · ${copied.get()} songs copied · ${tracks.size} selected${if(reviews > 0)" · $reviews changes need your choice"else""}",
                    bytes.get(),
                )
        } catch (e: CancellationException) {
            connectedPeer.value = null
            state.value = SyncState(message = "Sync interrupted; tap Sync to continue")
            throw e
        } catch (e: Exception) {
            connectedPeer.value = null
            state.value = SyncState(
                message = e.message ?: "Synchronization failed",
                stage = if (e is SyncPausedException) "Paused" else "Failed",
            )
            throw e
        }
    }

    private suspend fun apply(
        peer: Peer,
        entity: String,
        key: String,
        payload: JsonElement?,
        revision: Long,
        reviewDesktop: Boolean = true,
        reviewed: Boolean = false,
        updates: MutableMap<String, Track>? = null,
        removals: MutableSet<String>? = null,
        deferReview: Boolean = false,
    ) {
        val previous = g.dao.replica(peer.id, entity, key)
        if (!reviewed && previous != null && previous.revision >= revision) return
        val remote = payload.takeUnless { it == JsonNull }
        val pending = g.dao.pending(peer.id, entity, key)
        val old = if (entity == "tracks") g.dao.track(key) else null
        val phone = if (reviewDesktop) old?.takeUnless { it.deleted }?.let { g.library.trackWire(it) } else null
        val prior = previous?.payload?.let { JsonCodec.parseToJsonElement(it) }
        if (reviewDesktop && entity == "tracks" && old != null && needsDesktopReview(prior, remote, phone)) {
            if (deferReview) throw SyncReviewRequired()
            val response = exchange(peer, client(peer.fingerprint), "POST", "/review", buildJsonObject {
                put("key", key); put("revision", revision); put("phone", phone ?: JsonNull)
            })
            val id = response["conflict"]?.jsonPrimitive?.content
            if (id == null) {
                apply(peer, entity, key, payload, revision, reviewDesktop = false,
                    updates = updates, removals = removals)
            } else g.db.withTransaction {
                val latest = response["desktop"].takeUnless { it == JsonNull }
                val latestRevision = response.getValue("revision").jsonPrimitive.long
                g.dao.putConflict(SyncConflict(id, entity, key,
                    latest?.toString(), phone?.toString(), latestRevision,
                    JsonArray(changedTrackFields(latest, phone).map { JsonPrimitive(it) }).toString(), peer.id))
                g.dao.putReplica(Replica(peer.id, entity, key, latestRevision, latest?.toString()))
            }
            return
        }
        val base = previous?.payload?.let { JsonCodec.parseToJsonElement(it).jsonObject } ?: buildJsonObject {}
        val shown = visibleSyncPayload(remote, base, pending)
        g.db.withTransaction {
            if (shown == null) {
                when (entity) {
                    "tracks" ->
                        g.dao.track(key)?.let {
                            g.dao.putTrack(
                                it.removedFromLibrary()
                            )
                        }
                    "collections" -> g.dao.deleteCollection(key)
                    "members" -> g.dao.deleteMember(key)
                    "likes" -> g.dao.deleteLike(key)
                }
            } else {
                val p = shown.jsonObject
                when (entity) {
                    "tracks" -> {
                        val old = g.dao.track(key)
                        val values = p.toMutableMap()
                        values["id"] = JsonPrimitive(key)
                        values["uri"] = JsonPrimitive(old?.uri ?: "")
                        values["root"] = JsonPrimitive(old?.root ?: "")
                        values["managed"] = JsonPrimitive(old?.managed ?: false)
                        values["art"] =
                            JsonPrimitive(
                                if (old?.artHash == p["artHash"]?.jsonPrimitive?.content)
                                    old?.art ?: ""
                                else ""
                            )
                        val advertisedHash = p["audioHash"]?.jsonPrimitive?.content.orEmpty()
                        val desiredHash = advertisedHash.ifBlank { old?.hash.orEmpty() }
                        val localMatches = old != null && old.uri.isNotBlank() && desiredHash.isNotBlank() &&
                            (old.available && old.hash == desiredHash ||
                                runCatching { g.input(old.uri).use { it.sha256() } == desiredHash }.getOrDefault(false))
                        values["hash"] = JsonPrimitive(advertisedHash.ifBlank { old?.hash.orEmpty() })
                        values["size"] = p["audioSize"] ?: JsonPrimitive(old?.size ?: 0)
                        values["artHash"] = p["artHash"] ?: JsonPrimitive("")
                        values["available"] =
                            JsonPrimitive(
                                localMatches
                            )
                        values["deleted"] = JsonPrimitive(false)
                        val track = JsonCodec.decodeFromJsonElement<Track>(JsonObject(values))
                        g.dao.putTrack(track)
                        if (updates != null) updates[key] = track
                        else withContext(Dispatchers.Main) { g.engine.value?.updateTrack(track) }
                    }
                    "collections" -> {
                        val old = g.dao.collection(key)
                        g.dao.putCollection(
                            dev.sillyplayer.data.Collection(
                                key,
                                p.getValue("name").jsonPrimitive.content,
                                p.getValue("kind").jsonPrimitive.content,
                                if (old?.artHash == p["artHash"]?.jsonPrimitive?.content)
                                    old?.art ?: ""
                                else "",
                                p["artHash"]?.jsonPrimitive?.content ?: "",
                            )
                        )
                    }
                    "members" -> {
                        val old = g.dao.member(key)
                        val collection = p.getValue("collection").jsonPrimitive.content
                        g.dao.putMember(
                            Member(
                                key,
                                collection,
                                p.getValue("track").jsonPrimitive.content,
                                old?.position
                                    ?: ((g.dao.members(collection).maxOfOrNull { it.position }
                                        ?: -1) + 1),
                            )
                        )
                    }
                    "orders" -> {
                        val requested =
                            p.getValue("entries").jsonArray.map { it.jsonPrimitive.content }
                        for (m in normalizedOrder(requested, g.dao.members(key))) g.dao.putMember(m)
                    }
                    "likes" -> g.dao.putLike(Like(p["track"]?.jsonPrimitive?.content ?: key))
                    "counters" -> {
                        val c = JsonCodec.decodeFromJsonElement<Counter>(shown)
                        val old = g.dao.counterComponent(c.device, c.component)
                        g.dao.putCounter(mergeCounter(old, c))
                    }
                    "plays" -> g.dao.putPlay(JsonCodec.decodeFromJsonElement<Play>(shown))
                }
            }
            if (entity == "tracks" && remote == null) {
                // discard stale edits for the deleting peer, and forward the deletion only to other paired desktops that still know a live version of this record
                g.dao.deletePending(peer.id, entity, key)
                g.dao.deletePending("", entity, key)
                for (other in g.dao.peers().filter { it.id != peer.id }) {
                    val edit = g.dao.pending(other.id, entity, key)
                    val known = g.dao.replica(other.id, entity, key)
                    if (edit?.payload != null || known?.payload != null) {
                        g.dao.deletePending(other.id, entity, key)
                        g.dao.putOutbox(Outbox(peer = other.id, entity = entity, key = key,
                            base = edit?.base ?: known?.revision ?: 0, payload = null))
                    }
                }
            }
            g.dao.putReplica(Replica(peer.id, entity, key, revision, remote?.toString()))
        }
        if (entity == "tracks" && shown == null) {
            updates?.remove(key)
            if (removals != null) removals += key
            else withContext(Dispatchers.Main) { g.engine.value?.removeTracks(setOf(key)) }
        } else if (entity == "tracks") removals?.remove(key)
    }

    private suspend fun remap(entity: String, remote: String, canonical: String) {
        if (remote == canonical) return
        g.db.withTransaction {
            if (entity == "tracks") {
                val old = g.dao.track(remote)
                if (old != null) {
                    val existing = g.dao.track(canonical)
                    g.dao.putTrack(
                        old.copy(
                            id = canonical,
                            uri = existing?.uri?.takeIf { it.isNotBlank() } ?: old.uri,
                        )
                    )
                    g.dao.putTrack(old.copy(deleted = true, mergedInto = canonical))
                    for (m in g.dao.members().filter { it.track == remote }) g.dao.putMember(
                        m.copy(track = canonical)
                    )
                    if (g.dao.likes().any { it.track == remote }) {
                        g.dao.deleteLike(remote)
                        g.dao.putLike(Like(canonical))
                    }
                    for (c in g.dao.counters().filter { it.track == remote }) g.dao.putCounter(
                        c.copy(track = canonical)
                    )
                    for (play in g.dao.plays().filter { it.track == remote }) g.dao.putPlay(
                        play.copy(track = canonical)
                    )
                    withContext(Dispatchers.Main) {
                        g.engine.value?.remapTracks(
                            mapOf(remote to (g.dao.track(canonical) ?: old.copy(id = canonical)))
                        )
                    }
                    g.prefs.update {
                        it.copy(
                            queue =
                                it.queue.copy(
                                    entries =
                                        it.queue.entries.map { e ->
                                            if (e.trackId == remote)
                                                e.copy(
                                                    track = e.track.copy(id = canonical),
                                                    trackId = canonical,
                                                )
                                            else e
                                        }
                                )
                        )
                    }
                }
            } else if (entity == "members") {
                g.dao.member(remote)?.let { old ->
                    if (g.dao.member(canonical) == null) g.dao.putMember(old.copy(id = canonical))
                    g.dao.deleteMember(remote)
                }
            } else if (entity == "collections") {
                val old = g.dao.collection(remote)
                if (old != null) {
                    g.dao.putCollection(old.copy(id = canonical))
                    for (m in g.dao.members(remote)) g.dao.putMember(m.copy(collection = canonical))
                    g.dao.deleteCollection(remote)
                }
            }
        }
    }

    /** batch-check which content hashes desktop does not yet have so null falls back to per-file HEAD */
    private suspend fun checkMissingBlobs(
        peer: Peer,
        http: OkHttpClient,
        hashes: Set<String>,
    ): Set<String>? {
        if (hashes.isEmpty()) return emptySet()
        val missing = HashSet<String>()
        for (chunk in hashes.chunked(2000)) {
            val response =
                try {
                    exchange(
                        peer,
                        http,
                        "POST",
                        "/blobs/check",
                        buildJsonObject {
                            put("hashes", JsonArray(chunk.map { JsonPrimitive(it) }))
                        },
                    )
                } catch (error: SyncHttpException) {
                    if (error.status == 404 || error.status == 405) return null
                    throw error
                }
            val array = response["missing"] as? JsonArray ?: return null
            for (item in array) missing += item.jsonPrimitive.content
        }
        return missing
    }

    private suspend fun upload(peer: Peer, http: OkHttpClient, hash: String, uri: String): Long {
        val exists = retrySyncNetwork(onRetry = ::showRetry) {
            syncResponse(http, request(peer, "HEAD", "/blobs/$hash")) {
                when {
                    it.isSuccessful -> true
                    it.code == 404 -> false
                    else -> throw SyncHttpException(it.code, "Cannot check content: HTTP ${it.code}")
                }
            }
        }
        if (exists) return 0
        var offset = 0L
        var sent = 0L
        var stream = g.input(uri)
        var position = 0L
        val data = ByteArray(1024 * 1024)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (offset < position) {
                    stream.close()
                    stream = g.input(uri)
                    position = 0
                }
                while (position < offset) {
                    val skipped = stream.skip(offset - position)
                    if (skipped > 0) position += skipped
                    else {
                        require(stream.read() != -1) { "Source ended before resume position" }
                        position++
                    }
                }
                var n = 0
                while (n < data.size) {
                    val read = stream.read(data, n, data.size - n)
                    if (read < 0) break
                    n += read
                }
                position += n
                val complete = n < data.size
                val upload =
                    request(peer, "PUT", "/blobs/$hash")
                        .newBuilder()
                        .put(data.toRequestBody("application/octet-stream".toMediaType(), 0, n))
                        .header("X-Offset", offset.toString())
                        .header("X-Complete", if (complete) "1" else "0")
                        .build()
                var retryingCompletion = false
                val result = retrySyncNetwork(onRetry = {
                    showRetry(it)
                    retryingCompletion = complete
                }) {
                    // the desktop can publish the file then lose its final reply
                    // in that case the .part offset no longer exists, avoid restarting the file
                    val alreadyPublished = retryingCompletion && syncResponse(http,
                        request(peer, "HEAD", "/blobs/$hash"),
                    ) {
                        when {
                            it.isSuccessful -> true
                            it.code == 404 -> false
                            else -> throw SyncHttpException(it.code, "Cannot verify upload: HTTP ${it.code}")
                        }
                    }
                    if (alreadyPublished) buildJsonObject { put("offset", offset + n) } to true
                    else syncResponse(http, upload) { response ->
                        if (response.code == 409) {
                            val body = JsonCodec.parseToJsonElement(response.body!!.string()).jsonObject
                            body to false
                        } else syncJson(response) to true
                    }
                }
                val (body, accepted) = result
                if (!accepted) {
                    offset = body.getValue("offset").jsonPrimitive.long
                    require(offset >= 0) { "Invalid resume position" }
                } else {
                    val next = body["offset"]?.jsonPrimitive?.long ?: offset + n
                    require(next == offset + n) { "Desktop returned an invalid upload offset" }
                    offset = next
                    sent += n
                    if (complete) return sent
                }
            }
        } finally {
            stream.close()
        }
    }

    private fun showRetry(attempt: Int) {
        state.value = state.value.copy(stage = "Connection interrupted · retry $attempt/5")
    }

    private suspend fun download(peer: Peer, http: OkHttpClient, hash: String): File {
        val stage = state.value.stage.substringBefore(" · ")
        return retrySyncNetwork(onRetry = ::showRetry) {
            downloadSyncBlob(
                http,
                { request(peer, "GET", "/blobs/$hash") },
                hash,
                File(g.context.filesDir, "sync"),
            ) { done, total ->
                state.value = state.value.copy(
                    stage = "$stage · ${done / 1024} / ${total / 1024} KiB",
                )
            }
        }
    }

    private suspend fun publishTrackChanges(updates: Map<String, Track>, removals: Set<String>) {
        if (updates.isEmpty() && removals.isEmpty()) return
        withContext(Dispatchers.Main) {
            if (updates.isNotEmpty()) g.engine.value?.updateTracks(updates.values)
            if (removals.isNotEmpty()) g.engine.value?.removeTracks(removals)
        }
    }

    private suspend fun receiveChanges(peer: Peer, http: OkHttpClient, initial: Long): Long {
        var cursor = initial
        do {
            val response = exchange(peer, http, "GET", "/changes?cursor=$cursor")
            val updates = mutableMapOf<String, Track>()
            val removals = mutableSetOf<String>()
            try {
                for (chunk in response.getValue("changes").jsonArray.chunked(50)) {
                    // reviews can perform a network exchange
                    // keep those outside a Room transaction, new records and non-track pages can commit together
                    val canBatch = chunk.none { value ->
                        val row = value.jsonObject
                        val key = row.getValue("key").jsonPrimitive.content
                        row.getValue("entity").jsonPrimitive.content == "tracks" &&
                            ("tracks" to key) !in acceptedChanges && g.dao.track(key) != null
                    }
                    val chunkUpdates = mutableMapOf<String, Track>()
                    val chunkRemovals = mutableSetOf<String>()
                    suspend fun applyChunk(batched: Boolean) {
                        for (value in chunk) {
                            val row = value.jsonObject
                            val entity = row.getValue("entity").jsonPrimitive.content
                            val key = row.getValue("key").jsonPrimitive.content
                            apply(peer, entity, key, row["payload"], row.getValue("revision").jsonPrimitive.long,
                                reviewDesktop = (entity to key) !in acceptedChanges,
                                updates = if (batched) chunkUpdates else updates,
                                removals = if (batched) chunkRemovals else removals,
                                deferReview = batched)
                        }
                    }
                    if (canBatch) {
                        try {
                            g.db.withTransaction { applyChunk(true) }
                        } catch (_: SyncReviewRequired) {
                            // a local import raced the preparation check
                            // roll back and review individually, without holding a database transaction
                            applyChunk(false)
                            continue
                        }
                        updates.putAll(chunkUpdates)
                        for (key in chunkUpdates.keys) removals.remove(key)
                        for (key in chunkRemovals) { updates.remove(key); removals += key }
                    } else applyChunk(false)
                }
            } finally {
                withContext(NonCancellable) { publishTrackChanges(updates, removals) }
            }
            val next = response.getValue("cursor").jsonPrimitive.long
            require(next >= cursor && (!response.getValue("more").jsonPrimitive.boolean || next > cursor)) {
                "Desktop returned an invalid sync cursor"
            }
            cursor = next
            g.dao.putPeer((g.dao.peers().firstOrNull { it.id == peer.id } ?: peer).copy(cursor = cursor))
        } while (response.getValue("more").jsonPrimitive.boolean)
        acceptedChanges.clear()
        return cursor
    }

    /** only active while the app is visible, after an explicit successful connection */
    suspend fun watchReviews() {
        var interval = 5_000L
        while (currentCoroutineContext().isActive) {
            delay(interval)
            val id = connectedPeer.value ?: continue
            if (state.value.busy || g.resetting.value) continue
            g.syncGate.withLock {
                if (connectedPeer.value != id || state.value.busy) return@withLock
                val peer = g.dao.peers().firstOrNull { it.id == id } ?: return@withLock
                try {
                    val http = client(peer.fingerprint)
                    fetchConflicts(peer, http)
                    val resolved = acceptedChanges.isNotEmpty()
                    if (resolved) pendingMediaSyncPeers.add(id)
                    val cursor = receiveChanges(peer, http, peer.cursor)
                    g.dao.putPeer((g.dao.peers().firstOrNull { it.id == id } ?: peer).copy(cursor = cursor))
                    exchange(peer, http, "POST", "/ack", buildJsonObject { put("cursor", cursor) })
                    // mirror new phone edits into existing shared choices before a desktop click
                    for (c in g.dao.conflictsForPeer(id).filter { it.entity == "tracks" }) {
                        val phone = g.dao.track(c.key)?.takeUnless { it.deleted }?.let { g.library.trackWire(it, rehashArt = false) }
                        val saved = c.remote?.let { JsonCodec.parseToJsonElement(it) }
                        if (changedTrackFields(saved, phone).isNotEmpty()) {
                            exchange(peer, http, "POST", "/review", buildJsonObject {
                                put("key", c.key); put("revision", c.revision); put("phone", phone ?: JsonNull)
                            })
                        }
                    }
                    fetchConflicts(peer, http)
                    val remainingConflicts = g.dao.conflictsForPeer(id)
                    val edits = g.dao.syncBatch(id).any { it.entity !in listOf("counters", "plays") }
                    interval = if (resolved || edits || remainingConflicts.isNotEmpty() || cursor != peer.cursor)
                        5_000L else minOf(30_000L, interval + 5_000L)
                    // wait until all pending choices are reviewed before running media sync so reviewing changes one-by-one never loops over the whole playlist after each pick
                    if (remainingConflicts.isEmpty() && (id in pendingMediaSyncPeers || edits)) {
                        synchronizeUnlocked(id)
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    connectedPeer.value = null
                    state.value = SyncState(message = "Desktop disconnected: ${e.message}. Pending choices are kept; tap Sync now to reconnect.")
                }
            }
        }
    }

    private suspend fun fetchConflicts(peer: Peer, http: OkHttpClient) {
        val response = exchange(peer, http, "GET", "/conflicts")
        val remote = response.getValue("conflicts").jsonArray.map { value ->
            val c = value.jsonObject
            SyncConflict(c.getValue("id").jsonPrimitive.content,
                c.getValue("entity").jsonPrimitive.content, c.getValue("key").jsonPrimitive.content,
                c["local"]?.jsonPrimitive?.content, c["remote"]?.jsonPrimitive?.content,
                c.getValue("revision").jsonPrimitive.long, c["fields"]?.jsonPrimitive?.content ?: "[]", peer.id)
        }
        val remoteIds = remote.map { it.id }.toSet()
        val known = g.dao.conflictsForPeer(peer.id).associateBy { it.id }
        val removed = known.values.filter { it.id !in remoteIds }
        g.db.withTransaction {
            for (c in removed) {
                discardReviewedPending(c)
                g.dao.deleteConflict(c.id)
            }
            for (c in remote) if (known[c.id] != c) g.dao.putConflict(c)
        }
        for (c in removed) acceptedChanges += c.entity to c.key
    }

    private suspend fun discardReviewedPending(conflict: SyncConflict) {
        val pending = g.dao.pending(conflict.peer, conflict.entity, conflict.key)
        val baseline = conflict.remote ?: "null"
        val matches = pending == null || runCatching {
            JsonCodec.parseToJsonElement(pending.payload ?: "null") == JsonCodec.parseToJsonElement(baseline)
        }.getOrDefault(false)
        if (matches) g.dao.deletePending(conflict.peer, conflict.entity, conflict.key)
    }

    suspend fun resolveMany(snapshot: List<SyncConflict>, usePhone: Boolean): SyncResolutionSummary =
        g.syncGate.withLock {
            val choices = snapshotSongChoices(snapshot)
            if (choices.isEmpty()) return@withLock SyncResolutionSummary(message = "No song choices selected")
            val peer = g.dao.peers().firstOrNull { it.id == choices.first().peer }
                ?: error("Desktop is not paired")
            val http = client(peer.fingerprint)
            var resolved = 0
            var skipped = snapshot.size - choices.size
            var failed = 0
            val total = snapshot.size
            val oldServerPending = mutableListOf<SyncConflict>()
            var batchSupported = true
            var processed = skipped
            val appliedBatch = mutableListOf<SyncConflict>()
            val job = currentCoroutineContext().job
            resolutionJob = job
            fun summary(cancelled: Boolean = false, detail: String = ""): SyncResolutionSummary {
                val text = "$resolved song choices applied · $skipped skipped · $failed failed" +
                    if (detail.isBlank()) "" else " · $detail"
                return SyncResolutionSummary(resolved, skipped, failed, total, cancelled, text)
            }
            fun publish() {
                resolutionSummary.value = summary()
                state.value = SyncState(true, "Applying song choices $processed/$total",
                    if (total > 0) processed.toFloat() / total else 1f)
            }
            fun recordResolved(conflict: SyncConflict) {
                appliedBatch += conflict
                resolved++
            }
            suspend fun commitResolved() {
                if (appliedBatch.isEmpty()) return
                g.db.withTransaction {
                    for (conflict in appliedBatch) {
                        discardReviewedPending(conflict)
                        g.dao.deleteConflict(conflict.id)
                    }
                }
                for (conflict in appliedBatch) acceptedChanges += conflict.entity to conflict.key
                appliedBatch.clear()
            }
            publish()
            try {
                for (group in choices.chunked(100)) {
                    currentCoroutineContext().ensureActive()
                    val pending = group
                    val body = buildJsonObject {
                        put("usePhone", usePhone)
                        put("choices", JsonArray(pending.map { choice -> buildJsonObject {
                            put("id", choice.id); put("revision", choice.revision)
                            put("phoneVersion", choice.remote ?: "null")
                        } }))
                    }
                    require(body.toString().toByteArray().size <= 8 * 1024 * 1024) {
                        "The selected song choices exceed the desktop request limit; choose fewer songs"
                    }
                    var response: JsonObject? = null
                    if (batchSupported) {
                        try {
                            response = exchange(peer, http, "POST", "/resolve-batch", body)
                        } catch (error: SyncHttpException) {
                            if (error.status == 404 || error.status == 405) batchSupported = false
                            else throw error
                        }
                    }
                    if (batchSupported) {
                        val results = response?.get("results") as? JsonArray
                            ?: error("Desktop returned no choice results; remaining choices are kept")
                        val byId = pending.associateBy { it.id }
                        val reported = mutableSetOf<String>()
                        for (value in results) {
                            val result = value.jsonObject
                            val id = result.getValue("id").jsonPrimitive.content
                            val choice = byId[id] ?: error("Desktop returned an unknown song choice")
                            require(reported.add(id)) { "Desktop returned a duplicate song choice" }
                            when (result.getValue("status").jsonPrimitive.content) {
                                "resolved" -> recordResolved(choice)
                                "stale", "missing", "skipped" -> skipped++
                                else -> failed++
                            }
                        }
                        skipped += pending.size - reported.size
                        processed += pending.size
                    } else {
                        // older v1 desktops expose only /resolve
                        // keep the same captured baselines, show progress, and remain cancellable between choices
                        for (choice in pending) {
                            currentCoroutineContext().ensureActive()
                            try {
                                val result = exchange(peer, http, "POST", "/resolve", buildJsonObject {
                                    put("id", choice.id); put("usePhone", usePhone)
                                    put("revision", choice.revision); put("phoneVersion", choice.remote ?: "null")
                                })
                                when (result["status"]?.jsonPrimitive?.content) {
                                    "resolved" -> recordResolved(choice)
                                    "stale", "missing", "skipped" -> skipped++
                                    "error" -> failed++
                                    else -> oldServerPending += choice
                                }
                            } catch (error: SyncHttpException) {
                                if (error.status == 404) skipped++ else throw error
                            }
                            processed++
                            publish()
                        }
                    }
                    commitResolved()
                    publish()
                }
                fetchConflicts(peer, http)
                val remaining = g.dao.conflictsForPeer(peer.id).map { it.id }.toSet()
                for (choice in oldServerPending) {
                    if (choice.id in remaining) skipped++ else resolved++
                }
                if (resolved > 0) {
                    val latestPeer = g.dao.peers().firstOrNull { it.id == peer.id } ?: peer
                    val nextCursor = receiveChanges(latestPeer, http, latestPeer.cursor)
                    g.dao.putPeer(latestPeer.copy(cursor = nextCursor))
                    exchange(latestPeer, http, "POST", "/ack", buildJsonObject { put("cursor", nextCursor) })
                    pendingMediaSyncPeers.add(peer.id)
                }
                val result = summary(detail = "Changed and new choices remain for review")
                resolutionSummary.value = result
                state.value = SyncState(message = result.message, stage = "Choices applied", progress = 1f)
                if (resolved > 0 && remaining.isEmpty()) start(peer)
                result
            } catch (error: CancellationException) {
                withContext(NonCancellable) { commitResolved() }
                val result = summary(cancelled = true, detail = "Remaining choices are kept")
                resolutionSummary.value = result
                state.value = SyncState(message = result.message, stage = "Paused")
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable) { commitResolved() }
                val result = summary(detail = error.message ?: "Remaining choices are kept")
                resolutionSummary.value = result
                state.value = SyncState(message = result.message,
                    stage = if (error is SyncPausedException) "Paused" else "Failed")
                throw error
            } finally {
                if (resolutionJob == job) resolutionJob = null
            }
        }

    suspend fun resolve(conflict: SyncConflict, usePhone: Boolean) = g.syncGate.withLock {
        val peer = g.dao.peers().firstOrNull { it.id == conflict.peer } ?: error("Desktop is not paired")
        val current = g.dao.conflictsForPeer(peer.id).firstOrNull { it.id == conflict.id } ?: return@withLock
        require(current.revision == conflict.revision) { "Desktop changed again; review the updated choice" }
        val http = client(peer.fingerprint)
        val response = exchange(peer, http, "POST", "/resolve", buildJsonObject {
            put("id", conflict.id); put("usePhone", usePhone); put("revision", conflict.revision)
            put("phoneVersion", conflict.remote ?: "null")
        })
        val outcome = response["status"]?.jsonPrimitive?.contentOrNull
        if (outcome == "error" || outcome == "stale") {
            fetchConflicts(peer, http)
            error(response["message"]?.jsonPrimitive?.contentOrNull ?: "Desktop changed again; review the updated choice")
        }
        fetchConflicts(peer, http)
        val remaining = g.dao.conflictsForPeer(peer.id)
        require(remaining.none { it.id == conflict.id }) {
            "Desktop changed again; review the updated choice"
        }
        val latestPeer = g.dao.peers().firstOrNull { it.id == peer.id } ?: peer
        val nextCursor = receiveChanges(latestPeer, http, latestPeer.cursor)
        g.dao.putPeer(latestPeer.copy(cursor = nextCursor))
        exchange(latestPeer, http, "POST", "/ack", buildJsonObject { put("cursor", nextCursor) })
        pendingMediaSyncPeers.add(peer.id)
        if (remaining.isEmpty()) {
            start(latestPeer)
        } else {
            state.value =
                SyncState(
                    message = "Choice applied · ${remaining.size} changes remaining (changed audio transfers once reviewed)",
                )
        }
    }

}

class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        setForeground(
            workNotification(applicationContext, 43817, "SillyPlayer sync", "Synchronizing library")
        )
        return try {
            applicationContext.graph.sync.synchronize(
                inputData.getString("peer") ?: return Result.failure()
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
