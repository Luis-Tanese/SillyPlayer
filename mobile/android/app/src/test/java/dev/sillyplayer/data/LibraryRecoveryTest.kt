package dev.sillyplayer.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LibraryRecoveryTest {
    @Test
    fun portableSettingsRoundTripPreservesDeviceFieldsAndClampsBounds() {
        val source =
            Preferences(
                developerMode = true,
                palette = "Tokyo Night",
                light = true,
                master = 175f,
                onlineVolume = 80f,
                mode = 1,
                eqEnabled = true,
                eq = listOf(4f, 3f, 2f, 1f, 0f, -1f, -2f, 1f, 2f, 3f),
                eqPreset = "Custom",
                pauseFade = true,
                fadeMs = 650,
                musicDir = "content://device-specific-a",
                sleepEnd = 12345L,
            )
        val encoded = JsonCodec.encodeToString(PortableSettings.from(source))
        val decoded = JsonCodec.decodeFromString<PortableSettings>(encoded)
        val target = Preferences(musicDir = "content://device-specific-b", sleepEnd = 99999L)
        val applied = decoded.applyTo(target)

        assertEquals("Tokyo Night", applied.palette)
        assertTrue(applied.light)
        assertEquals(175f, applied.master, 0.01f)
        assertEquals(80f, applied.onlineVolume, 0.01f)
        assertEquals(1, applied.mode)
        assertTrue(applied.eqEnabled)
        assertEquals(source.eq, applied.eq)
        assertEquals("Custom", applied.eqPreset)
        assertTrue(applied.pauseFade)
        assertEquals(650, applied.fadeMs)
        // Device-local state must stay untouched.
        assertEquals("content://device-specific-b", applied.musicDir)
        assertEquals(99999L, applied.sleepEnd)
    }

    @Test
    fun legacyJsonRecoveryRoundTripAndMetadataPreservation() {
        val hash = "0123456789abcdef".repeat(4)
        val track =
            Track(
                id = "track-1",
                uri = "content://example/song.mp3",
                title = "Edited Title",
                artist = "Edited Artist",
                hash = hash,
                speed = 1.25f,
                nightcore = true,
                volumePercent = 140f,
            )
        val library =
            RecoveryLibrary(
                created = 1700000000000L,
                tracks = listOf(track),
                collections = listOf(Collection("col-1", "Favorites")),
                members = listOf(Member("mem-1", "col-1", "track-1", 0)),
                likes = listOf(Like("track-1")),
            )
        val bytes = encodeRecovery(library)
        val decoded = decodeRecovery(bytes)
        assertEquals(library.created, decoded.created)
        assertEquals("Edited Title", decoded.tracks.single().title)

        val indexed =
            Track(
                id = "track-new",
                uri = "content://reinstalled/song.mp3",
                title = "Raw Filename",
                artist = "Unknown artist",
                hash = hash,
                art = "file:///cache/art.img",
                available = true,
            )
        val recovered = recoverIndexedMetadata(indexed, track.copy(available = false))
        assertEquals("track-1", recovered.id)
        assertEquals("Edited Title", recovered.title)
        assertEquals(1.25f, recovered.speed, 0.001f)
        assertTrue(recovered.nightcore)
        assertEquals(140f, recovered.volumePercent, 0.001f)
        assertEquals("content://reinstalled/song.mp3", recovered.uri)
        assertEquals("file:///cache/art.img", recovered.art)
        assertTrue(recovered.available)
    }

    @Test
    fun splitsMultiArtistStringsWhilePreservingBandNames() {
        assertEquals(listOf("ANGUISH", "EXILED", "Elfass"), splitArtists("ANGUISH, EXILED, Elfass"))
        assertEquals(
            listOf("ANGUISH", "Lieless", "Тая из Хентая", "ily"),
            splitArtists("ANGUISH, Lieless, Тая из Хентая, ily"),
        )
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), splitArtists("Artist A (feat. Artist B, Artist C)"))
        assertEquals(listOf("Artist A", "Artist B"), splitArtists("Artist A ft. Artist B"))
        assertEquals(listOf("Artist A", "Artist B"), splitArtists("Artist A; Artist B"))
        assertEquals(listOf("Artist A", "Artist B"), splitArtists("Artist A / Artist B"))
        assertEquals(listOf("AC/DC"), splitArtists("AC/DC"))
        assertEquals(listOf("Simon & Garfunkel"), splitArtists("Simon & Garfunkel"))
        assertEquals(listOf("Lil Nas X"), splitArtists("Lil Nas X"))
        assertEquals(listOf("ANGUISH"), splitArtists("ANGUISH, anguish"))
        assertEquals(listOf("Unknown artist"), splitArtists("   "))
    }
}
