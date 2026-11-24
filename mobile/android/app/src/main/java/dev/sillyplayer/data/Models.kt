package dev.sillyplayer.data

import androidx.room.*
import java.util.UUID
import kotlinx.serialization.Serializable

fun uuid(): String = UUID.randomUUID().toString()

@Serializable
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: String = uuid(),
    val uri: String = "",
    val title: String = "",
    val artist: String = "Unknown artist",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: Int = 0,
    val number: Int = 0,
    val duration: Double = 0.0,
    val art: String = "",
    val available: Boolean = true,
    val deleted: Boolean = false,
    val artHash: String = "",
    val fileName: String = "",
    val added: Long = System.currentTimeMillis(),
    val modified: Long = 0,
    val size: Long = 0,
    val hash: String = "",
    val speed: Float = 1f,
    val nightcore: Boolean = false,
    val volumePercent: Float = 100f,
    val sourceUrl: String = "",
    val uploader: String = "",
    val videoTitle: String = "",
    val sourceProvider: String = "unknown",
    val sourceItemId: String = "",
    val mergedInto: String = "",
    val managed: Boolean = false,
    val root: String = "",
)

@Serializable
@Entity(tableName = "collections")
data class Collection(
    @PrimaryKey val id: String = uuid(),
    val name: String,
    val kind: String = "playlist",
    val art: String = "",
    val artHash: String = "",
)

@Serializable
@Entity(
    tableName = "members",
    indices = [Index(value = ["collection", "position"]), Index("track")],
)
data class Member(
    @PrimaryKey val id: String = uuid(),
    val collection: String,
    val track: String,
    val position: Int,
)

@Serializable @Entity(tableName = "likes") data class Like(@PrimaryKey val track: String)

@Serializable
@Entity(tableName = "roots")
data class Root(
    @PrimaryKey val id: String = uuid(),
    val uri: String,
    val name: String,
    val available: Boolean = true,
)

@Serializable
@Entity(tableName = "jobs")
data class DownloadJob(
    @PrimaryKey val id: String = uuid(),
    val provider: String,
    val sourceId: String = "",
    val url: String,
    val title: String,
    val uploader: String = "",
    val artist: String = "Unknown artist",
    val art: String = "",
    val kind: String = "Original",
    val destination: String,
    val state: String = "Queued",
    val bytes: Long = 0,
    val total: Long = -1,
    val progress: Float = -1f,
    val error: String = "",
    val output: String = "",
    val stage: String = "",
    val created: Long = System.currentTimeMillis(),
    val refreshes: Int = 0,
)

@Serializable
@Entity(tableName = "download_members", indices = [Index("job")])
data class DownloadMember(
    @PrimaryKey val id: String = uuid(),
    val collection: String,
    val job: String,
    val position: Int,
)

@Serializable
@Entity(tableName = "searches", primaryKeys = ["provider", "query"])
data class Search(
    val provider: String,
    val query: String,
    val used: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "plays", indices = [Index("track")])
data class Play(
    @PrimaryKey val session: String = uuid(),
    val track: String,
    val playedAt: Long = System.currentTimeMillis(),
    val heardMs: Long = 0,
    val qualified: Boolean = false,
)

@Serializable
@Entity(tableName = "counters", primaryKeys = ["device", "component"])
data class Counter(
    val device: String,
    val track: String,
    val milliseconds: Long = 0,
    val plays: Long = 0,
    val lastPlayed: Long = 0,
    val component: String = track,
)

@Serializable
@Entity(tableName = "trash")
data class TrashEntry(
    @PrimaryKey val track: String,
    val original: String,
    val stored: String,
    val name: String,
    val hash: String,
    val deletedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "outbox")
data class Outbox(
    @PrimaryKey val opId: String = uuid(),
    val peer: String = "",
    val entity: String,
    val key: String,
    val base: Long,
    val payload: String?,
    val created: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "replicas", primaryKeys = ["peer", "entity", "key"])
data class Replica(
    val peer: String,
    val entity: String,
    val key: String,
    val revision: Long = 0,
    val payload: String? = null,
)

@Serializable
@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val fingerprint: String,
    val credential: String,
    val cursor: Long = 0,
    val allMedia: Boolean = false,
    val selected: String = "[]",
)

@Serializable
@Entity(tableName = "conflicts")
data class SyncConflict(
    @PrimaryKey val id: String,
    val entity: String,
    val key: String,
    val local: String?,
    val remote: String?,
    val revision: Long,
    val fields: String = "[]",
    val peer: String = "",
)

@Serializable
data class QueueEntry(
    val id: String = uuid(),
    @kotlinx.serialization.Transient val track: Track = Track(id = ""),
    val trackId: String = track.id,
)

@Serializable
data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = 0,
    val position: Long = 0,
    val context: String = "Songs",
    val shuffleOrder: List<String> = emptyList(),
)

@Serializable
data class Preferences(
    val developerMode: Boolean = false,
    val palette: String = "Catppuccin",
    val light: Boolean = false,
    val master: Float = 100f,
    val onlineVolume: Float = 100f,
    val mode: Int = 0,
    val eqEnabled: Boolean = false,
    val eq: List<Float> = List(10) { 0f },
    val eqPreset: String = "Flat",
    val pauseFade: Boolean = false,
    val fadeMs: Int = 300,
    val crossfadeMs: Int = 0,
    val duckAudio: Boolean = true,
    val musicDir: String = "",
    val queue: QueueState = QueueState(),
    val sleepEnd: Long = 0,
    val updateNotifications: Boolean = true,
    val lastNotifiedVersion: String = "",
)

@Serializable
data class Source(
    val provider: String = "youtube",
    val id: String = "",
    val title: String = "",
    val uploader: String = "",
    val artist: String = "Unknown artist",
    val url: String = "",
    val thumbnail: String = "",
    val duration: Double = 0.0,
    val playable: Boolean = true,
    val reason: String = "",
)

@Serializable
data class Stream(
    val source: Source,
    val url: String,
    val mime: String = "",
    val suffix: String = "m4a",
    val bitrate: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val expires: Long = System.currentTimeMillis() + 30000,
    val delivery: String = "PROGRESSIVE_HTTP",
)

@Serializable
@Entity(tableName = "restore_ids", primaryKeys = ["origin", "kind", "remote"])
data class RestoreId(val origin: String, val kind: String, val remote: String, val local: String)
