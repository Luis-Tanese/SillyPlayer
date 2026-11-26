package dev.sillyplayer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("sillyplayer")
val JsonCodec = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

class PreferenceStore(private val context: Context, scope: CoroutineScope) {
    val loaded = kotlinx.coroutines.CompletableDeferred<Preferences>()
    private val key = stringPreferencesKey("settings")
    private val queueKey = stringPreferencesKey("queue-occurrences")
    private val shuffleKey = stringPreferencesKey("queue-shuffle-order")
    // keep volatile playback position outside DataStore so periodic saves don't emit full prefs snapshots
    private val posPrefs = context.getSharedPreferences("playback_pos", Context.MODE_PRIVATE)
    private data class ShuffleCache(val text: String, val ids: List<String>)
    @Volatile private var shuffleCache = ShuffleCache("[]", emptyList())

    private data class QueueCache(val text: String, val entries: List<QueueEntry>)

    @Volatile private var queueCache = QueueCache("[]", emptyList())

    private fun read(data: androidx.datastore.preferences.core.Preferences): Preferences {
        val base =
            runCatching { JsonCodec.decodeFromString<Preferences>(data[key] ?: "{}") }
                .getOrDefault(Preferences())
        val text = data[queueKey] ?: "[]"
        val entries =
            queueCache.takeIf { it.text === text || it.text == text }?.entries
                ?: runCatching { JsonCodec.decodeFromString<List<QueueEntry>>(text) }
                    .getOrDefault(emptyList())
                    .also { queueCache = QueueCache(text, it) }
        val shuffleText = data[shuffleKey]
        val shuffle = if (shuffleText == null) base.queue.shuffleOrder else
            shuffleCache.takeIf { it.text === shuffleText || it.text == shuffleText }?.ids
                ?: runCatching { JsonCodec.decodeFromString<List<String>>(shuffleText) }
                    .getOrDefault(emptyList()).also { shuffleCache = ShuffleCache(shuffleText, it) }
        return base.copy(queue = base.queue.copy(entries = entries, shuffleOrder = shuffle))
    }

    val state =
        context.preferences.data
            .map(::read)
            .onEach { loaded.complete(it) }
            .stateIn(scope, SharingStarted.Eagerly, Preferences())
    // ANDROID_ID is scoped to this signing key, Android user and device
    // hash it, never use model names to identify a phone or expose the platform identifier
    val deviceKey: String =
        runCatching {
                android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID,
                    )
                    ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
                    ?.let { id ->
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(("dev.sillyplayer:pair:" + id).toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    }
                    .orEmpty()
            }
            .getOrDefault("")
    val deviceId: String =
        context.getSharedPreferences("identity", Context.MODE_PRIVATE).let { prefs ->
            prefs.getString("id", null)
                ?: (if (deviceKey.isNotEmpty())
                        java.util.UUID.nameUUIDFromBytes(deviceKey.toByteArray()).toString()
                    else uuid())
                    .also { prefs.edit().putString("id", it).commit() }
        }

    fun savePosition(entryId: String, index: Int, position: Long) {
        posPrefs.edit()
            .putString("entryId", entryId)
            .putInt("index", index)
            .putLong("position", position.coerceAtLeast(0L))
            .apply()
    }

    fun loadPosition(queue: QueueState): Long {
        val currentId = queue.entries.getOrNull(queue.index)?.id ?: return queue.position
        if (posPrefs.getString("entryId", null) == currentId && posPrefs.getInt("index", -1) == queue.index) {
            return posPrefs.getLong("position", queue.position).coerceAtLeast(0L)
        }
        return queue.position
    }

    suspend fun update(block: (Preferences) -> Preferences) {
        context.preferences.edit { data ->
            val old = read(data)
            val changed = block(old)
            if (old == changed) return@edit
            if (old.queue != changed.queue) {
                val entryId = changed.queue.entries.getOrNull(changed.queue.index)?.id.orEmpty()
                savePosition(entryId, changed.queue.index, changed.queue.position)
            }
            if (old.queue.entries !== changed.queue.entries && (
                old.queue.entries.size != changed.queue.entries.size ||
                    old.queue.entries.indices.any { i ->
                        old.queue.entries[i].id != changed.queue.entries[i].id ||
                            old.queue.entries[i].trackId != changed.queue.entries[i].trackId
                    })
            )
                data[queueKey] = JsonCodec.encodeToString(changed.queue.entries)
            if ((data[shuffleKey] == null && changed.queue.shuffleOrder.isNotEmpty()) ||
                (old.queue.shuffleOrder !== changed.queue.shuffleOrder && old.queue.shuffleOrder != changed.queue.shuffleOrder))
                data[shuffleKey] = JsonCodec.encodeToString(changed.queue.shuffleOrder)
            data[key] =
                JsonCodec.encodeToString(
                    changed.copy(queue = changed.queue.copy(entries = emptyList(), shuffleOrder = emptyList()))
                )
        }
    }
}
