package dev.sillyplayer.online

import dev.sillyplayer.data.Source
import dev.sillyplayer.data.Stream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StreamCacheTest {
    private val stream = Stream(Source(), "audio")

    @Test fun unrelatedLookupsProceedAndIdenticalRequestsShareTheResult() = runBlocking {
        val cache = StreamCache()
        val release = CompletableDeferred<Unit>()
        val slow = async(start = CoroutineStart.UNDISPATCHED) {
            cache.resolve("one", false, false) { release.await(); stream }
        }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
            cache.resolve("one", false, false) { error("duplicated an active lookup") }
        }
        assertEquals(stream, withTimeout(1000) { cache.resolve("two", false, false) { stream } })
        release.complete(Unit)
        assertEquals(slow.await(), duplicate.await())
    }

    @Test fun audioVideoExpiryAndExplicitRetriesAreIndependent() = runBlocking {
        val cache = StreamCache()
        cache.resolve("item", false, false) { stream }
        val video = stream.copy(url = "video")
        assertEquals(video, cache.resolve("item", true, false) { video })
        assertEquals(video, cache.resolve("item", true, false) { error("missing video cache") })
        val expired = stream.copy(expires = 0)
        assertEquals(expired, cache.resolve("item", false, true) { expired })
        assertEquals(stream, cache.resolve("item", false, false) { stream })
    }
}
