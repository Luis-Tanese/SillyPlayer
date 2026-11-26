package dev.sillyplayer.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.sillyplayer.R
import dev.sillyplayer.data.Track
import dev.sillyplayer.data.splitArtists
import dev.sillyplayer.graph
import dev.sillyplayer.ui.Palettes
import dev.sillyplayer.ui.canonicalPalette
import dev.sillyplayer.widget.PlayerWidget
import dev.sillyplayer.widget.WidgetState
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlaybackService : MediaLibraryService() {
    private lateinit var engine: PlaybackEngine
    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun buildNotificationButtons(liked: Boolean, mode: Int): ImmutableList<CommandButton> {
        // use Media3's built-in notification icons so SystemUI doesn't scale up full-bleed stroke vectors
        val likeButton =
            CommandButton.Builder(
                if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
            )
                .setDisplayName(if (liked) "Unlike" else "Like")
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW, CommandButton.SLOT_BACK_SECONDARY)
                .build()
        val modeButton =
            CommandButton.Builder(
                when (mode) {
                    1 -> CommandButton.ICON_REPEAT_ONE
                    2 -> CommandButton.ICON_SHUFFLE_ON
                    else -> CommandButton.ICON_REPEAT_ALL
                }
            )
                .setDisplayName(
                    when (mode) {
                        1 -> "Repeat one"
                        2 -> "Shuffle"
                        else -> "Repeat queue"
                    }
                )
                .setSessionCommand(SessionCommand(ACTION_CYCLE_MODE, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW, CommandButton.SLOT_FORWARD_SECONDARY)
                .build()
        return ImmutableList.of(likeButton, modeButton)
    }

    private fun browsableItem(
        id: String,
        title: String,
        subtitle: String = "",
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()

    private fun playableItem(contextKey: String, track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId("play:$contextKey:${track.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title.ifBlank { "Untitled" })
                    .setArtist(track.artist.ifBlank { "Unknown artist" })
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.art.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private suspend fun resolveChildren(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        fun slice(items: List<MediaItem>): List<MediaItem> {
            if (pageSize <= 0) return items
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = ((page + 1) * pageSize).coerceAtMost(items.size)
            return if (from < to) items.subList(from, to) else emptyList()
        }
        return when {
            parentId == ROOT_ID -> {
                // only count available tracks instead of loading the full track table for root items
                val trackCount = withContext(Dispatchers.IO) { graph.dao.countAvailableTracks() }
                slice(
                    listOf(
                        browsableItem(SONGS_ID, "Songs", "$trackCount songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                        browsableItem(LIKED_ID, "Liked Songs", "", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                        browsableItem(PLAYLISTS_ID, "Playlists", "", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                        browsableItem(ARTISTS_ID, "Artists", "", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                    )
                )
            }
            parentId == SONGS_ID -> {
                val rows =
                    withContext(Dispatchers.IO) {
                        if (pageSize > 0) graph.dao.availableTracksPage(pageSize, page * pageSize)
                        else graph.dao.tracks().filter { it.available }
                    }
                rows.map { playableItem("songs", it) }
            }
            parentId == PLAYLISTS_ID -> {
                val collections = withContext(Dispatchers.IO) { graph.dao.collections() }
                slice(
                    collections
                        .filter { it.kind == "playlist" || it.kind == "custom" }
                        .map { c -> browsableItem("playlist:${c.id}", c.name, "", MediaMetadata.MEDIA_TYPE_PLAYLIST) }
                )
            }
            parentId == LIKED_ID -> {
                val (tracks, likes) =
                    withContext(Dispatchers.IO) {
                        graph.dao.tracks().filter { it.available } to
                            graph.dao.likes().mapTo(HashSet()) { it.track }
                    }
                slice(tracks.filter { it.id in likes }.map { playableItem("liked", it) })
            }
            parentId.startsWith("playlist:") -> {
                val cid = parentId.removePrefix("playlist:")
                val items =
                    withContext(Dispatchers.IO) {
                        val members = graph.dao.members(cid)
                        val ids = members.map { it.track }.distinct()
                        val byId =
                            ids.chunked(400)
                                .flatMap { graph.dao.tracksByIds(it) }
                                .filter { it.available && !it.deleted }
                                .associateBy { it.id }
                        members.mapNotNull { byId[it.track]?.let { t -> playableItem(parentId, t) } }
                    }
                slice(items)
            }
            parentId == ARTISTS_ID -> {
                val tracks = withContext(Dispatchers.IO) { graph.dao.tracks().filter { it.available } }
                val seen = LinkedHashMap<String, Pair<String, Int>>()
                for (t in tracks) {
                    for (artist in splitArtists(t.artist)) {
                        val key = artist.lowercase(Locale.ROOT)
                        val prev = seen[key]
                        seen[key] = (prev?.first ?: artist) to ((prev?.second ?: 0) + 1)
                    }
                }
                slice(
                    seen.entries
                        .sortedBy { it.value.first.lowercase(Locale.ROOT) }
                        .map { (key, info) ->
                            val countLabel = if (info.second == 1) "1 song" else "${info.second} songs"
                            browsableItem("artist:$key", info.first, countLabel, MediaMetadata.MEDIA_TYPE_ARTIST)
                        }
                )
            }
            parentId.startsWith("artist:") -> {
                val key = parentId.removePrefix("artist:")
                val tracks = withContext(Dispatchers.IO) { graph.dao.tracks().filter { it.available } }
                slice(
                    tracks
                        .filter { t -> splitArtists(t.artist).any { it.lowercase(Locale.ROOT) == key } }
                        .map { playableItem(parentId, it) }
                )
            }
            else -> emptyList()
        }
    }

    private suspend fun playFromBrowseId(mediaId: String): Boolean {
        if (!mediaId.startsWith("play:")) return false
        val rest = mediaId.removePrefix("play:")
        val sep = rest.lastIndexOf(':')
        if (sep <= 0) return false
        val contextKey = rest.substring(0, sep)
        val trackId = rest.substring(sep + 1)
        val tracks = withContext(Dispatchers.IO) { graph.dao.tracks().filter { it.available } }
        val byId = tracks.associateBy { it.id }
        val (queueTracks, contextLabel) =
            when {
                contextKey == "songs" -> tracks to "Songs"
                contextKey == "liked" -> {
                    val likes = withContext(Dispatchers.IO) { graph.dao.likes().mapTo(HashSet()) { it.track } }
                    tracks.filter { it.id in likes } to "Liked Songs"
                }
                contextKey.startsWith("playlist:") -> {
                    val cid = contextKey.removePrefix("playlist:")
                    val name = withContext(Dispatchers.IO) { graph.dao.collection(cid)?.name } ?: "Playlist"
                    val members = withContext(Dispatchers.IO) { graph.dao.members(cid) }
                    members.mapNotNull { byId[it.track] } to "Playlist: $name"
                }
                contextKey.startsWith("artist:") -> {
                    val key = contextKey.removePrefix("artist:")
                    val matched = tracks.filter { t ->
                        splitArtists(t.artist).any { it.lowercase(Locale.ROOT) == key }
                    }
                    val display = matched.firstOrNull()?.let { t ->
                        splitArtists(t.artist).firstOrNull { it.lowercase(Locale.ROOT) == key }
                    } ?: "Artist"
                    matched to "Artist: $display"
                }
                else -> tracks to "Songs"
            }
        val target = queueTracks.firstOrNull { it.id == trackId } ?: byId[trackId] ?: return false
        val startIndex = queueTracks.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        engine.ready.await()
        engine.playTrack(target, queueTracks.ifEmpty { listOf(target) }, contextLabel, startIndex)
        return true
    }

    override fun onCreate() {
        super.onCreate()
        val provider =
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL)
                .setChannelName(R.string.playback_channel)
                .build()
        provider.setSmallIcon(R.drawable.ic_music_2)
        setMediaNotificationProvider(provider)
        engine = PlaybackEngine(graph)
        graph.engine.value = engine

        val forwarding =
            object : ForwardingPlayer(engine.player) {
                // System, headset, Android Auto and lock-screen transport use the same transition path as the UI
                override fun seekToNext() = engine.next()
                override fun seekToNextMediaItem() = engine.next()
                override fun seekToPrevious() = engine.previous()
                override fun seekToPreviousMediaItem() = engine.previous()
                override fun play() = engine.resumeActive()
                override fun pause() = engine.pause()
            }

        val callback =
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sessionCommands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                            .buildUpon()
                            .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                            .add(SessionCommand(ACTION_CYCLE_MODE, Bundle.EMPTY))
                            .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_TOGGLE_LIKE -> {
                            val trackId = engine.state.value.music?.id
                            if (!trackId.isNullOrBlank()) {
                                serviceScope.launch(Dispatchers.IO) { graph.library.like(trackId) }
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ACTION_CYCLE_MODE -> {
                            engine.cycleMode()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> =
                    Futures.immediateFuture(
                        LibraryResult.ofItem(browsableItem(ROOT_ID, "SillyPlayer"), params)
                    )

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                    serviceScope.launch {
                        try {
                            val slice = resolveChildren(parentId, page, pageSize)
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(slice), params))
                        } catch (e: Exception) {
                            future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                        }
                    }
                    return future
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String,
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    if (mediaId == ROOT_ID) {
                        return Futures.immediateFuture(
                            LibraryResult.ofItem(browsableItem(ROOT_ID, "SillyPlayer"), null)
                        )
                    }
                    val future = SettableFuture.create<LibraryResult<MediaItem>>()
                    serviceScope.launch {
                        try {
                            val trackId = mediaId.substringAfterLast(':')
                            val track = withContext(Dispatchers.IO) { graph.dao.track(trackId) }
                            if (track != null) {
                                future.set(LibraryResult.ofItem(playableItem("songs", track), null))
                            } else {
                                future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                            }
                        } catch (_: Exception) {
                            future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                        }
                    }
                    return future
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val chosen = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
                    if (chosen != null && chosen.mediaId.startsWith("play:")) {
                        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        serviceScope.launch {
                            try {
                                playFromBrowseId(chosen.mediaId)
                                future.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        engine.resumptionItems(),
                                        engine.state.value.index,
                                        0L,
                                    )
                                )
                            } catch (e: Exception) {
                                future.setException(e)
                            }
                        }
                        return future
                    }
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val result =
                        SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    serviceScope.launch {
                        try {
                            engine.ready.await()
                            result.set(
                                MediaSession.MediaItemsWithStartPosition(
                                    engine.resumptionItems(),
                                    engine.state.value.index,
                                    engine.state.value.musicPosition,
                                )
                            )
                        } catch (e: Exception) {
                            result.setException(e)
                        }
                    }
                    return result
                }
            }

        val initialButtons = buildNotificationButtons(liked = false, mode = 0)
        session =
            MediaLibrarySession.Builder(this, forwarding, callback)
                .setSessionActivity(openPlayer())
                .setMediaButtonPreferences(initialButtons)
                .build()

        serviceScope.launch {
            combine(
                engine.state.map { it.music?.id to it.mode }.distinctUntilChanged(),
                graph.library.likes,
            ) { (trackId, mode), likes ->
                val liked = trackId != null && trackId in likes
                liked to mode
            }
                .distinctUntilChanged()
                .collect { (liked, mode) ->
                    val buttons = buildNotificationButtons(liked, mode)
                    session?.setMediaButtonPreferences(buttons)
                }
        }

        serviceScope.launch {
            try {
                engine.ready.await()
            } catch (e: Exception) {
                return@launch
            }
            combine(engine.state, graph.prefs.state) { state, prefs ->
                    val palette = Palettes[canonicalPalette(prefs.palette)] ?: Palettes.getValue("Catppuccin")
                    val offset = if (prefs.light) 6 else 0
                    fun color(index: Int) =
                        (0xff000000L or palette[offset + index].toLong(16)).toInt()
                    WidgetState(
                        state.music?.title?.ifBlank { "Untitled" } ?: "SillyPlayer",
                        if (state.owner == "online" && state.music != null)
                            "Music paused · Browse active"
                        else state.music?.artist ?: "Select a song in the app",
                        state.music?.art.orEmpty(),
                        state.music != null,
                        state.owner == "music" && engine.player.playWhenReady,
                        color(0),
                        color(3),
                        color(5),
                    )
                }
                .distinctUntilChanged()
                .collect { state ->
                    withContext(Dispatchers.IO) {
                        PlayerWidget.publish(this@PlaybackService, state)
                    }
                }
        }
    }

    private fun openPlayer(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, dev.sillyplayer.MainActivity::class.java)
                .setAction(PlayerWidget.OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action !in listOf(PlayerWidget.TOGGLE, PlayerWidget.PREVIOUS, PlayerWidget.NEXT))
            return result
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.playback_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music_2)
                .setContentTitle("SillyPlayer")
                .setContentText("Restoring music controls")
                .setContentIntent(openPlayer())
                .setStyle(session?.let { MediaStyleNotificationHelper.MediaStyle(it) })
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        else startForeground(NOTIFICATION_ID, notification)
        serviceScope.launch {
            try {
                withTimeout(15000) { engine.ready.await() }
                check(engine.state.value.music != null) {
                    "Open SillyPlayer and select an available song first"
                }
                when (action) {
                    PlayerWidget.TOGGLE -> engine.toggleMusic()
                    PlayerWidget.PREVIOUS -> engine.previous()
                    PlayerWidget.NEXT -> engine.next()
                }
            } catch (e: Exception) {
                graph.message.value = e.message ?: "Music could not resume"
                if (!engine.player.playWhenReady) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        serviceScope.cancel()
        PlayerWidget.publish(this, PlayerWidget.load(this).copy(playing = false))
        graph.engine.value = null
        session?.release()
        engine.close()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "sillyplayer-playback"
        const val NOTIFICATION_ID = 43820
        const val ACTION_TOGGLE_LIKE = "dev.sillyplayer.action.TOGGLE_LIKE"
        const val ACTION_CYCLE_MODE = "dev.sillyplayer.action.CYCLE_MODE"
        private const val ROOT_ID = "root"
        private const val SONGS_ID = "songs"
        private const val LIKED_ID = "liked"
        private const val PLAYLISTS_ID = "playlists"
        private const val ARTISTS_ID = "artists"
    }
}
