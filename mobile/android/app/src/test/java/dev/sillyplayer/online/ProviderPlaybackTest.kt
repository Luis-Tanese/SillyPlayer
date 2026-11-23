package dev.sillyplayer.online

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/** Opt in to exercising the real services without requiring an audio device. */
class ProviderPlaybackTest {
    @Test fun youtube() = checkStream("YouTube", "https://www.youtube.com/watch?v=jNQXAC9IVRw")
    @Test fun soundCloud() = checkStream("SoundCloud", "https://soundcloud.com/forss/flickermood")

    private fun checkStream(service: String, url: String) {
        assumeTrue(System.getenv("SILLYPLAYER_LIVE_ANDROID_TEST") == "1")
        val http = OkHttpClient()
        val transport = ExtractorTransport(http)
        NewPipe.init(transport)
        val stream = runBlocking {
            transport.lookup {
                val extractor = NewPipe.getService(service).getStreamExtractor(url)
                extractor.fetchPage()
                selectAudio(extractor.audioStreams)
            }
        }
        val request = Request.Builder().url(stream.content)
            .header("User-Agent", "Mozilla/5.0 (Android) SillyPlayer/0.1").header("Range", "bytes=0-1023").build()
        http.newCall(request).execute().use {
            assertTrue("Audio request returned HTTP ${it.code}", it.isSuccessful)
            assertTrue(it.body!!.byteStream().read() >= 0)
        }
    }
}
