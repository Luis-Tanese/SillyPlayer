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
        assertFalse(prefs.allowPlaylistDuplicates)
        assertTrue(prefs.eqPresets.isEmpty())
        assertTrue(prefs.pinnedPlaylists.isEmpty())
        assertTrue(prefs.pinnedArtists.isEmpty())
    }

    @Test
    fun normalizeYoutubeCollectionUrlHandlesMusicLinksAndTrackingParams() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PLWyngZ1wSkETpYiqDXHs7N1kUkuXj_VIK",
            dev.sillyplayer.online.normalizeYoutubeCollectionUrl(
                "https://music.youtube.com/playlist?list=PLWyngZ1wSkETpYiqDXHs7N1kUkuXj_VIK&drli=1"
            ),
        )
        assertEquals(
            "https://www.youtube.com/playlist?list=PLWyngZ1wSkETpYiqDXHs7N1kUkuXj_VIK",
            dev.sillyplayer.online.normalizeYoutubeCollectionUrl(
                "https://music.youtube.com/playlist/?list=PLWyngZ1wSkETpYiqDXHs7N1kUkuXj_VIK&drli=1"
            ),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=daPIDjeLjPo&list=RDdaPIDjeLjPo",
            dev.sillyplayer.online.normalizeYoutubeCollectionUrl(
                "https://music.youtube.com/watch?v=daPIDjeLjPo&list=RDdaPIDjeLjPo&drli=1"
            ),
        )
    }

    @Test
    fun mergeEqPresetsHandlesLwwUpdatesDeletionsAndNameCollisions() {
        val local =
            listOf(
                EqPreset(id = "p1", name = "Bass", gains = listOf(20f, -20f, 3f), updatedAt = 100L),
                EqPreset(id = "p2", name = "Chill", gains = listOf(1f, 2f), updatedAt = 200L),
            )
        val remote =
            listOf(
                EqPreset(id = "p1", name = "Bass", gains = listOf(4f, 5f, 0f), updatedAt = 300L),
                EqPreset(id = "p2", name = "Chill", gains = emptyList(), updatedAt = 400L, deleted = true),
                EqPreset(id = "p3", name = "Nightcore", gains = listOf(-1f, 0f, 2f), updatedAt = 500L),
            )
        val merged = mergeEqPresets(local, remote)
        val bass = merged.first { it.id == "p1" }
        assertFalse(bass.deleted)
        assertEquals(10, bass.gains.size)
        assertEquals(listOf(4f, 5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), bass.gains)

        val chill = merged.first { it.id == "p2" }
        assertTrue(chill.deleted)

        val nightcore = merged.first { it.id == "p3" }
        assertEquals("Nightcore", nightcore.name)
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

    @Test
    fun folderTreePreservesLiteralPlusSignsInSafFolderNames() {
        val root =
            Root(
                id = "r1",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                name = "Music",
            )
        val track =
            Track(
                id = "tp",
                title = "Plus Song",
                root = "r1",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock+Pop%2Ftrack.mp3",
            )
        val music = buildAndroidFolderView(listOf(track), listOf(root), "Music")
        assertEquals(listOf("Rock+Pop"), music.folders.map { it.name })
    }

    @Test
    fun mergeEqStateSyncsEnabledPresetGainsAndHandlesRemotePresetChanges() {
        val local = EqStateSync(enabled = false, preset = "Flat", gains = List(10) { 0f }, updatedAt = 100L)
        val remote = EqStateSync(enabled = true, preset = "Bass", gains = listOf(4f, 3f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), updatedAt = 200L)
        val presets = listOf(EqPreset(id = "p1", name = "Bass", gains = listOf(6f, 5f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), updatedAt = 250L))
        val merged = mergeEqState(local, remote, presets)
        assertTrue(merged.enabled)
        assertEquals("Bass", merged.preset)
        assertEquals(6f, merged.gains[0])

        val afterDelete = mergeEqState(merged, null, listOf(EqPreset(id = "p1", name = "Bass", gains = emptyList(), updatedAt = 300L, deleted = true)))
        assertEquals("Custom", afterDelete.preset)
        assertEquals(6f, afterDelete.gains[0])
    }

    @Test
    fun portableSettingsPreservesPinsSmartShuffleAndEqStateTimestamp() {
        val prefs =
            Preferences(
                smartShuffle = true,
                updateNotifications = false,
                pinnedPlaylists = listOf("pl-1"),
                pinnedArtists = listOf("artist:anguish"),
                eqStateUpdatedAt = 123456L,
            )
        val restored = PortableSettings.from(prefs).applyTo(Preferences())
        assertTrue(restored.smartShuffle)
        assertFalse(restored.updateNotifications)
        assertEquals(listOf("pl-1"), restored.pinnedPlaylists)
        assertEquals(listOf("artist:anguish"), restored.pinnedArtists)
        assertEquals(123456L, restored.eqStateUpdatedAt)
    }

    @Test
    fun canonicalPaletteDeduplicatesAndResolvesLegacyAliases() {
        assertEquals("Catppuccin", dev.sillyplayer.ui.canonicalPalette("Catppuccin Mocha"))
        assertEquals("Gruvbox", dev.sillyplayer.ui.canonicalPalette("Gruvbox Dark"))
        assertEquals("Solarized", dev.sillyplayer.ui.canonicalPalette("Solarized Light"))
        assertEquals(dev.sillyplayer.ui.Palettes.size, dev.sillyplayer.ui.Palettes.values.toSet().size)
    }

    @Test
    fun extractSourceMetadataParsesSillyPlayerCommentsPurlAndFilenames() {
        val fromComment =
            extractSourceMetadata(
                listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ · Uploader: Rick · Original title: Never Gonna Give You Up"),
                "renamed.mp3",
            )
        assertNotNull(fromComment)
        assertEquals("youtube", fromComment!!.provider)
        assertEquals("dQw4w9WgXcQ", fromComment.itemId)
        assertEquals("Rick", fromComment.uploader)
        assertEquals("Never Gonna Give You Up", fromComment.title)

        val fromFile = extractSourceMetadata(emptyList(), "G.H.O.S.T. [aBcDeFgHiJk] 1234abcd.opus")
        assertNotNull(fromFile)
        assertEquals("youtube", fromFile!!.provider)
        assertEquals("aBcDeFgHiJk", fromFile.itemId)
        assertEquals("https://www.youtube.com/watch?v=aBcDeFgHiJk", fromFile.url)

        val fromSoundCloud = extractSourceMetadata(listOf("https://soundcloud.com/artist/track"), "track.mp3")
        assertNotNull(fromSoundCloud)
        assertEquals("soundcloud", fromSoundCloud!!.provider)
    }

    @Test
    fun perceptualFadeGainUsesSmoothQuadraticCurveAndClampsBounds() {
        assertEquals(0f, dev.sillyplayer.media.perceptualFadeGain(-0.5f), 0.0001f)
        assertEquals(0f, dev.sillyplayer.media.perceptualFadeGain(0f), 0.0001f)
        assertEquals(0.0625f, dev.sillyplayer.media.perceptualFadeGain(0.25f), 0.0001f)
        assertEquals(0.25f, dev.sillyplayer.media.perceptualFadeGain(0.5f), 0.0001f)
        assertEquals(1f, dev.sillyplayer.media.perceptualFadeGain(1f), 0.0001f)
        assertEquals(1f, dev.sillyplayer.media.perceptualFadeGain(2f), 0.0001f)
    }

    @Test
    fun migration1To2DeclaresAllSecondaryIndexes() {
        assertEquals(1, LibraryDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, LibraryDatabase.MIGRATION_1_2.endVersion)
        val statements = LibraryDatabase.MIGRATION_1_2_STATEMENTS
        val expected = listOf(
            "index_tracks_deleted_mergedInto_added",
            "index_tracks_uri",
            "index_tracks_hash",
            "index_plays_qualified_playedAt",
            "index_plays_qualified_track_playedAt",
            "index_outbox_peer_entity_key",
            "index_outbox_peer_created",
            "index_outbox_key",
            "index_replicas_key",
            "index_conflicts_peer_entity_key",
            "index_conflicts_key",
        )
        assertEquals(expected.size, statements.size)
        for (name in expected) {
            assertTrue("Missing index $name", statements.any { it.contains("`$name`") && it.startsWith("CREATE INDEX IF NOT EXISTS ") })
        }
    }

    @Test
    fun smartShuffleLargeLibraryAndSingleArtistAreExactPermutations() {
        val now = 1_700_000_000_000L
        val entries =
            List(1500) { i ->
                val t = Track(id = "t$i", title = "Song $i", artist = "Artist ${i % 30}")
                QueueEntry(id = "e$i", track = t, trackId = t.id)
            }
        val lastPlayed = entries.associate { it.trackId to (now - (it.trackId.removePrefix("t").toLong() % 10L) * 86_400_000L) }
        val order =
            dev.sillyplayer.media.smartShuffledQueueOrder(
                entries = entries,
                index = 0,
                previous = emptyList(),
                lastPlayedByTrack = lastPlayed,
                nowMs = now,
                random = kotlin.random.Random(42),
            )
        assertEquals(1500, order.size)
        assertEquals("e0", order.first())
        assertEquals(1500, order.toSet().size)
        val byId = entries.associateBy { it.id }
        for (i in 1 until order.size) {
            assertNotEquals(byId.getValue(order[i]).track.artist, byId.getValue(order[i - 1]).track.artist)
        }

        val mono = List(200) { i ->
            val t = Track(id = "m$i", title = "Song $i", artist = "Solo Artist")
            QueueEntry(id = "me$i", track = t, trackId = t.id)
        }
        val monoOrder =
            dev.sillyplayer.media.smartShuffledQueueOrder(
                entries = mono,
                index = 0,
                previous = emptyList(),
                lastPlayedByTrack = emptyMap(),
                nowMs = now,
                random = kotlin.random.Random(7),
            )
        assertEquals(200, monoOrder.size)
        assertEquals(200, monoOrder.toSet().size)
    }

    @Test
    fun thumbnailCandidatesFastPathsLocalUrisAndInsightsReusesCalendar() {
        assertEquals(
            listOf("file:///data/user/0/dev.sillyplayer/files/art/indexed/abc.img"),
            dev.sillyplayer.online.thumbnailCandidates("file:///data/user/0/dev.sillyplayer/files/art/indexed/abc.img"),
        )
        assertEquals(
            listOf("content://media/external/audio/albumart/1"),
            dev.sillyplayer.online.thumbnailCandidates("content://media/external/audio/albumart/1"),
        )

        val tz = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_700_050_000_000L // midday UTC
        val track = Track(id = "t1", title = "Track 1", artist = "Artist A")
        val plays =
            List(600) { i ->
                Play(session = "s$i", track = "t1", playedAt = now - (i % 5) * 86_400_000L, heardMs = 60_000L, qualified = true)
            }
        val insights = computeListeningInsights("week", plays, emptyList(), listOf(track), nowMs = now, timeZone = tz)
        assertEquals(600L, insights.totalPlays)
        assertEquals(5, insights.activeDays)
        assertEquals(5, insights.streakDays)
        assertEquals("Artist A", insights.topArtistName)
    }

    @Test
    fun equalizerProcessorPassthroughGainOnlyAndStereoEqWorkCleanly() {
        val proc = dev.sillyplayer.media.EqualizerProcessor()
        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(48000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        proc.configure(format)
        proc.flush()

        fun makePcm(samples: ShortArray): java.nio.ByteBuffer =
            java.nio.ByteBuffer.allocateDirect(samples.size * 2)
                .order(java.nio.ByteOrder.nativeOrder())
                .apply {
                    for (s in samples) putShort(s)
                    flip()
                }

        fun readShorts(buf: java.nio.ByteBuffer): ShortArray {
            val out = ShortArray(buf.remaining() / 2)
            buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(out)
            return out
        }

        val input = ShortArray(128) { i -> (kotlin.math.sin(i * 0.15) * 10000.0).toInt().toShort() }
        // 1. passthrough path
        proc.settings.set(dev.sillyplayer.media.EqSettings(enabled = false))
        proc.signalGain = 1f
        proc.queueInput(makePcm(input))
        val passOut = readShorts(proc.output)
        assertArrayEquals(input, passOut)

        // 2. gain-only path (2x boost with clipping clamp)
        proc.signalGain = 2f
        val gainInput = shortArrayOf(1000, -1000, 20000, -20000)
        proc.queueInput(makePcm(gainInput))
        val gainOut = readShorts(proc.output)
        assertArrayEquals(shortArrayOf(2000, -2000, 32767, -32768), gainOut)

        // 3. stereo 10-band EQ path
        proc.signalGain = 1f
        proc.settings.set(dev.sillyplayer.media.EqSettings(enabled = true, gains = listOf(6f, 4f, 2f, 0f, -2f, 0f, 3f, 4f, 2f, 0f)))
        proc.queueInput(makePcm(input))
        val eqOut = readShorts(proc.output)
        assertEquals(input.size, eqOut.size)
        assertTrue(eqOut.any { it.toInt() != 0 })
        proc.flush()
    }

    @Test
    fun copyAndSha256MatchesStandaloneSha256AndPreservesBytes() {
        val payload = ByteArray(300 * 1024) { i -> ((i * 31 + 7) and 0xff).toByte() }
        val expected = java.io.ByteArrayInputStream(payload).sha256()
        val copied = java.io.ByteArrayOutputStream()
        val actual = java.io.ByteArrayInputStream(payload).copyAndSha256(copied)
        assertEquals(expected, actual)
        assertArrayEquals(payload, copied.toByteArray())
    }
}
