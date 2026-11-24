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

    @Test
    fun preferencesDefaultsHaveSmartShuffleOffAndEmptyPins() {
        val prefs = Preferences()
        assertFalse(prefs.smartShuffle)
        assertTrue(prefs.pinnedPlaylists.isEmpty())
        assertTrue(prefs.pinnedArtists.isEmpty())
    }

    @Test
    fun buildAndroidFolderViewHandlesSafTreeAndFileUris() {
        val root =
            Root(
                id = "r1",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                name = "Music",
            )
        val t1 =
            Track(
                id = "t1",
                title = "Root Song",
                root = "r1",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Froot.mp3",
            )
        val t2 =
            Track(
                id = "t2",
                title = "Classic Song",
                root = "r1",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock%2FClassic%2Fsong.mp3",
            )
        val t3 =
            Track(
                id = "t3",
                title = "Synced Song",
                uri = "file:///data/user/0/dev.sillyplayer/files/music/Synced/Chill/track.m4a",
            )

        val top = buildAndroidFolderView(listOf(t1, t2, t3), listOf(root), "")
        assertEquals(listOf("Music", "Synced"), top.folders.map { it.name })
        assertEquals(2, top.folders.first { it.name == "Music" }.trackCount)
        assertEquals(1, top.folders.first { it.name == "Music" }.subfolderCount)

        val music = buildAndroidFolderView(listOf(t1, t2, t3), listOf(root), "Music")
        assertEquals(1, music.tracks.size)
        assertEquals("Root Song", music.tracks.first().title)
        assertEquals(listOf("Rock"), music.folders.map { it.name })
        assertEquals("", music.parentPath)

        val classic = buildAndroidFolderView(listOf(t1, t2, t3), listOf(root), "Music/Rock/Classic")
        assertEquals(1, classic.tracks.size)
        assertEquals("Classic Song", classic.tracks.first().title)
        assertEquals("Music/Rock", classic.parentPath)
        assertEquals(listOf("Folders", "Music", "Rock", "Classic"), classic.breadcrumbs.map { it.name })

        val chill = buildAndroidFolderView(listOf(t1, t2, t3), listOf(root), "Synced/Chill")
        assertEquals(1, chill.tracks.size)
        assertEquals("Synced Song", chill.tracks.first().title)
    }
}
