package dev.sillyplayer.online

import org.junit.Assert.*
import org.junit.Test

class ThumbnailUrlsTest {
    @Test fun missingAndMalformedMetadataStillUseVideoId() {
        val expected = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertEquals(expected, thumbnailCandidates("", "dQw4w9WgXcQ").first())
        assertEquals(expected, thumbnailCandidates("broken", "dQw4w9WgXcQ").first())
        assertEquals(3, thumbnailCandidates("", "dQw4w9WgXcQ").size)
    }
    @Test fun providerOriginalSurvivesUntilFallback() {
        val original = "https://i1.sndcdn.com/artworks-123-original.jpg?signature=abc"
        val stored = sourceThumbnail(original, "")
        assertEquals(original, stored)
        assertEquals(listOf(original.replace("original", "t300x300"), original,
            original.replace("original", "large")), thumbnailCandidates(stored))
        val id = "dQw4w9WgXcQ"
        val signed = "https://example.org/thumbnail.jpg?signature=abc"
        assertEquals(signed, thumbnailCandidates(sourceThumbnail(signed, id), id)[1])
        assertEquals(thumbnailCandidates("", id).first(), sourceThumbnail("broken", id))
    }
    @Test fun normalizedUrlsKeepProviderFallbacks() {
        val candidates = thumbnailCandidates("//i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        assertEquals(3, candidates.size)
        assertTrue(candidates.all { it.startsWith("https://") })
        assertEquals(listOf("https://example.org/cover.png"), thumbnailCandidates("https://example.org/cover.png"))
        assertTrue(thumbnailCandidates("").isEmpty())
        assertTrue(thumbnailCandidates("", "invalid-id").isEmpty())
    }
}
