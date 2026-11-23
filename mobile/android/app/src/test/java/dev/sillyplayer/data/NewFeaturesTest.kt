package dev.sillyplayer.data

import dev.sillyplayer.sync.parseDiscoveryReply
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class NewFeaturesTest {
    @Test
    fun cleanSongTitleStripsJunkAndPreservesMeaningfulSuffixes() {
        assertEquals("Midnight City", cleanSongTitle("Midnight City (Official Audio)"))
        assertEquals("Midnight City", cleanSongTitle("Midnight City (Official Music Video) [HD]"))
        assertEquals("Midnight City", cleanSongTitle("Midnight City [Lyrics]"))
        assertEquals("Midnight City", cleanSongTitle("Midnight City | Official Video"))
        assertEquals("Midnight City (Remix)", cleanSongTitle("Midnight City (Remix) (Official Audio)"))
        assertEquals("Midnight City (Acoustic)", cleanSongTitle("Midnight City (Acoustic)"))
        assertEquals("(Official Video)", cleanSongTitle("(Official Video)"))
    }

    @Test
    fun m3uFormatAndParseRoundTrip() {
        val tracks =
            listOf(
                Track(id = "t1", title = "Song One", artist = "Artist A", duration = 185.4, fileName = "song1.mp3"),
                Track(id = "t2", title = "Song Two", artist = "Unknown artist", duration = 210.0, fileName = "song2.flac"),
            )
        val m3u = formatM3u(tracks)
        assertTrue(m3u.startsWith("#EXTM3U\n"))
        val parsed = parseM3u("\uFEFF" + m3u)
        assertEquals(2, parsed.size)
        assertEquals("song1.mp3", parsed[0].path)
        assertEquals("Artist A", parsed[0].artist)
        assertEquals("Song One", parsed[0].title)
        assertEquals(185, parsed[0].duration)
        assertEquals("song2.flac", parsed[1].path)
        assertEquals("", parsed[1].artist)
        assertEquals("Song Two", parsed[1].title)
    }

    @Test
    fun parseDiscoveryReplyValidatesPayload() {
        val fp = "a".repeat(64)
        val valid =
            """{"service":"sillyplayer-sync","v":1,"deviceId":"desk-1","name":"MyPC","port":43817,"fp":"$fp"}"""
        val desktop = parseDiscoveryReply(valid, "/192.168.1.42")
        assertNotNull(desktop)
        assertEquals("desk-1", desktop!!.deviceId)
        assertEquals("MyPC", desktop.name)
        assertEquals("192.168.1.42:43817", desktop.host)
        assertEquals(fp, desktop.fingerprint)

        assertNull(parseDiscoveryReply("""{"service":"other","v":1,"deviceId":"d","port":43817,"fp":"$fp"}""", "192.168.1.42"))
        assertNull(parseDiscoveryReply("""{"service":"sillyplayer-sync","v":1,"deviceId":"d","port":43817,"fp":"short"}""", "192.168.1.42"))
    }

    @Test
    fun computeListeningInsightsSummarizesDayWeekMonth() {
        val tz = TimeZone.getTimeZone("UTC")
        // 2026-06-15 15:00:00 UTC (Monday)
        val nowMs = 1781535600000L
        val t1 = Track(id = "t1", title = "Alpha", artist = "ANGUISH, ily")
        val t2 = Track(id = "t2", title = "Beta", artist = "ANGUISH")
        val plays =
            listOf(
                Play(session = "s1", track = "t1", playedAt = nowMs - 3600_000L, qualified = true), // 14:00 today
                Play(session = "s2", track = "t2", playedAt = nowMs - 1800_000L, qualified = true), // 14:30 today
                Play(session = "s3", track = "t1", playedAt = nowMs - 86400_000L, qualified = true), // yesterday
            )
        val counters =
            listOf(
                Counter(device = "d", track = "t1", plays = 2, milliseconds = 240_000L, lastPlayed = nowMs),
                Counter(device = "d", track = "t2", plays = 1, milliseconds = 120_000L, lastPlayed = nowMs),
            )

        val day = computeListeningInsights("day", plays, counters, listOf(t1, t2), nowMs, tz)
        assertEquals(2L, day.totalPlays)
        assertEquals("ANGUISH", day.topArtistName)
        assertEquals(2L, day.topArtistPlays)
        assertEquals(6, day.buckets.size)
        assertEquals(2, day.buckets[3].count) // 12p..4p block
        assertEquals(2, day.streakDays)

        val week = computeListeningInsights("week", plays, counters, listOf(t1, t2), nowMs, tz)
        assertEquals(3L, week.totalPlays)
        assertEquals(2, week.activeDays)
        assertEquals(7, week.buckets.size)
        assertEquals("Today", week.buckets.last().label)
        assertEquals(2, week.buckets.last().count)
        assertEquals(1, week.buckets[5].count)

        val month = computeListeningInsights("month", plays, counters, listOf(t1, t2), nowMs, tz)
        assertEquals(30, month.buckets.size)
        assertEquals(3L, month.totalPlays)
    }
}
