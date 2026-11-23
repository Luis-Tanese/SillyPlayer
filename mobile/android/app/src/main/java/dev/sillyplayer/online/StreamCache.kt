package dev.sillyplayer.online

import dev.sillyplayer.data.Stream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** a download and playback can share a lookup without blocking lookups for other tracks */
internal class StreamCache {
    private data class Entry(val lock: Mutex = Mutex(), var value: Stream? = null)
    private val entries = LinkedHashMap<Pair<String, Boolean>, Entry>(32, .75f, true)

    suspend fun resolve(url: String, video: Boolean, refresh: Boolean, load: suspend () -> Stream): Stream {
        val entry = synchronized(entries) {
            entries.getOrPut(url to video) { Entry() }.also {
                if (entries.size > 32) entries.remove(entries.keys.first())
            }
        }
        return entry.lock.withLock {
            val cached = entry.value
            if (!refresh && cached != null && cached.expires > System.currentTimeMillis()) return@withLock cached
            entry.value = null
            load().also { entry.value = it }
        }
    }
}
