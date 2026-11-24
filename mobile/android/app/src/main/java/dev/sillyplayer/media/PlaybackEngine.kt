package dev.sillyplayer.media

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.room.withTransaction
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class PlaybackState(
    val owner: String = "music",
    val track: Track? = null,
    val music: Track? = null,
    val online: Track? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val musicPosition: Long = 0,
    val onlinePosition: Long = 0,
    val duration: Long = 0,
    val buffering: Boolean = false,
    val error: String = "",
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = 0,
    val context: String = "Songs",
    val sleepEnd: Long = 0,
    val mode: Int = 0,
    val upcomingQueue: List<QueueEntry> = emptyList(),
)

data class PlaybackProgress(val musicPosition: Long = 0, val onlinePosition: Long = 0)

class PlaybackEngine(private val g: Graph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eq = EqualizerProcessor()
    fun dancePositionMs():Long = if(owner=="music") player.currentPosition else local.position
    private val renderers =
        object : DefaultRenderersFactory(g.context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(false)
                        .setAudioProcessors(arrayOf(eq))
                        .build()
            }
            .apply {
                setEnableDecoderFallback(true)
                forceDisableMediaCodecAsynchronousQueueing()
            }
    private val trackSelector = DefaultTrackSelector(g.context)
    val ready = CompletableDeferred<Unit>()
    private val httpSource = OkHttpDataSource.Factory(g.online.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build())
    private var onlineHeaders: Map<String, String> = emptyMap()
    private var onlineMime: String? = null
    val player =
        ExoPlayer.Builder(g.context, renderers)
            .setTrackSelector(trackSelector)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(g.context, httpSource))
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3000, 30000, 500, 1000)
                    .setBackBuffer(3000, false)
                    .setTargetBufferBytes(8 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    val state = MutableStateFlow(PlaybackState())
    val progress = MutableStateFlow(PlaybackProgress())
    private var local = QueueState()
    private var online: Track? = null
    private var onlinePosition = 0L
    private var owner = "music"
    private var master = 100f
    private var onlineVolume = 100f
    private var mode = 0
    private var requestedMode: Int? = null
    private var changingQueue = false
    private var upcoming = emptyList<QueueEntry>()
    private var viewEntries: List<QueueEntry>? = null
    private var viewShuffle: List<String>? = null
    private var viewIndex = -1
    private var viewMode = -1
    private var viewOwner = ""
    private var lastPlayerShuffle: ShuffleOrder? = null
    private var fade: Job? = null
    private var fadeGain = 1f
    private var transitionGain = 1f
    private var transitionFadeJob: Job? = null
    private var crossfadeMs = 0
    private var duckAudio = true
    private var previewGain: Pair<String, Float>? = null
    private var sleepFired = 0L
    private var playSession: Play? = null
    private var repairedAttemptTrackId = ""
    private var sessionOccurrence: String? = null
    private var counted = 0L
    private var lastTick = SystemClock.elapsedRealtime()
    private var lastSave = 0L
    private var listeningRunning = false
    private val advancing = MutableStateFlow(false)
    private var sleepJob: Job? = null
    private var scheduledSleep = 0L
    private var pendingSave: Job? = null
    private var countedQualified = false
    private var prefsMaster = 100f
    private var prefsOnline = 100f

    init {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (owner == "music") {
                        local = local.copy(index = player.currentMediaItemIndex.coerceAtLeast(0))
                        if (
                            playSession == null ||
                                sessionOccurrence != mediaItem?.mediaId ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                        )
                            startListening()
                        if (
                            crossfadeMs > 0 &&
                                player.playWhenReady &&
                                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                        ) {
                            startTransitionFadeIn()
                        } else {
                            resetTransitionFade()
                        }
                    }
                    applyTrack()
                    refreshQueueOrder()
                    updateTransitionBoundary()
                    publish()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && owner == "music") {
                        val index = if (mode == 1) player.currentMediaItemIndex else player.nextMediaItemIndex
                        if (index in local.entries.indices) selectMusic(index, 0, true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    accountListening()
                    listeningRunning = isPlaying && owner == "music"
                    advancing.value = isPlaying
                    if (!isPlaying) checkpoint()
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (!changingQueue && events.containsAny(Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        val actualMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) 1 else if (player.shuffleModeEnabled) 2 else 0
                        if (actualMode != mode && requestedMode == null) {
                            mode = actualMode
                            g.task { g.prefs.update { it.copy(mode = actualMode) } }
                        }
                        if (refreshQueueOrder() && ready.isCompleted) save()
                    }
                    updateTransitionBoundary()
                    publish()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val diagnostic = playbackDiagnostic(error.errorCodeName, error)
                    android.util.Log.e("SillyPlayer", diagnostic)
                    g.playbackError.value = diagnostic
                    g.task {
                        java.io
                            .File(g.context.filesDir, "last-playback-error.txt")
                            .writeText(diagnostic)
                    }
                    val cause = generateSequence<Throwable>(error) { it.cause }.take(8).last()
                    val active = if (owner == "music") currentTrack() else null
                    val isSafMissing =
                        active != null &&
                            (cause is IllegalArgumentException ||
                                cause is java.io.FileNotFoundException ||
                                cause is SecurityException)
                    if (isSafMissing && active != null && repairedAttemptTrackId != active.id) {
                        repairedAttemptTrackId = active.id
                        scope.launch {
                            val repaired = withContext(Dispatchers.IO) { g.library.repairTrackUri(active.id) }
                            if (repaired != null && repaired.uri != active.uri && owner == "music" && currentTrack()?.id == active.id) {
                                restoreLocal(true)
                            } else {
                                g.message.value =
                                    "Audio file not found for \"${active.title}\". Locate it in Settings → Library or rescan."
                                publish("Audio file not found: ${active.title}")
                            }
                        }
                        return
                    }
                    g.message.value =
                        if (isSafMissing && active != null)
                            "Audio file not found for \"${active.title}\". Locate it in Settings → Library or rescan."
                        else
                            "Playback: ${error.errorCodeName} · ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(140)}"
                    publish(diagnostic)
                }
            }
        )
        scope.launch {
            g.prefs.state.collect { p ->
                if (p.master != prefsMaster) {
                    master = p.master
                    prefsMaster = p.master
                }
                if (p.onlineVolume != prefsOnline) {
                    onlineVolume = p.onlineVolume
                    prefsOnline = p.onlineVolume
                }
                if (scheduledSleep != p.sleepEnd) {
                    scheduledSleep = p.sleepEnd
                    sleepJob?.cancel()
                    sleepJob = if (p.sleepEnd > 0) scope.launch {
                        delay((p.sleepEnd - System.currentTimeMillis()).coerceAtLeast(0))
                        if (g.prefs.state.value.sleepEnd == p.sleepEnd && sleepFired != p.sleepEnd) {
                            sleepFired = p.sleepEnd
                            pause()
                            sleep(0)
                        }
                    } else null
                }
                if (requestedMode == p.mode) requestedMode = null
                applyPlaybackMode(requestedMode ?: p.mode)
                crossfadeMs = p.crossfadeMs.coerceIn(0, 8000)
                if (crossfadeMs == 0) resetTransitionFade()
                if (duckAudio != p.duckAudio) {
                    duckAudio = p.duckAudio
                    // Media3 ducks AUDIO_CONTENT_TYPE_MUSIC on transient notifications and pauses AUDIO_CONTENT_TYPE_SPEECH instead of ducking
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(
                                if (duckAudio) C.AUDIO_CONTENT_TYPE_MUSIC
                                else C.AUDIO_CONTENT_TYPE_SPEECH
                            )
                            .build(),
                        true,
                    )
                }
                eq.settings.set(EqSettings(p.eqEnabled, p.eq))
                writeVolume()
                publish()
            }
        }
        scope.launch {
            try {
                val p = g.prefs.loaded.await()
                val indexed =
                    withContext(Dispatchers.IO) {
                        p.queue.entries.map { it.trackId }.distinct().chunked(400)
                            .flatMap { g.dao.tracksByIds(it) }.associateBy { it.id }
                    }
                // a user selection made during disk loading takes precedence over saved playback
                if (local.entries.isEmpty()) {
                    local = restoreQueue(p.queue, indexed)
                    restoreLocal(false)
                }
                ready.complete(Unit)
            } catch (e: Exception) {
                ready.completeExceptionally(e)
                g.message.value = "Could not restore playback: ${e.message}"
                return@launch
            }
            combine(advancing, g.foreground) { playing, visible -> playing to visible }
                .distinctUntilChanged()
                .collectLatest { (playing, visible) ->
                    if (!playing) return@collectLatest
                    while (isActive) {
                        delay(playbackTickIntervalMs(playing, visible) ?: return@collectLatest)
                        tick()
                    }
                }
        }
    }

    private fun applyPlaybackMode(value: Int) {
        val wasShuffle = player.shuffleModeEnabled
        mode = value.coerceIn(0, 2)
        val repeat = if (mode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        changingQueue = true
        try {
            if (mode == 2 && !wasShuffle) {
                val previous=upcomingPlaybackQueue(local.entries,local.index,0,local.shuffleOrder).map { it.id }
                applyShuffleOrder(shuffledQueueOrder(local.entries, local.index, previous))
            }
            if (player.repeatMode != repeat) player.repeatMode = repeat
            // repeat-one changes only end-of-song behavior, preserving the visible order
            if (mode != 1 && player.shuffleModeEnabled != (mode == 2)) player.shuffleModeEnabled = mode == 2
        } finally { changingQueue = false }
        refreshQueueOrder()
        updateTransitionBoundary()
    }

    private fun applyShuffleOrder(ids: List<String>) {
        if (ids.isEmpty() || local.entries.isEmpty()) return
        val order = normalizedQueueShuffle(ids, local.entries)
        local = local.copy(shuffleOrder = order)
        if (owner == "music" && player.mediaItemCount == local.entries.size) {
            val indices = local.entries.withIndex().associate { it.value.id to it.index }
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.map { indices.getValue(it) }.toIntArray(), Random.nextLong()))
        }
    }

    /** rebuild only after mode, timeline, occurrence, or queue data changes */
    private fun refreshQueueOrder(): Boolean {
        if (changingQueue) return false
        val activeOrder = if (owner == "music" && player.mediaItemCount == local.entries.size) player.shuffleOrder else null
        var orderChanged = false
        if (activeOrder != null && (activeOrder !== lastPlayerShuffle || local.entries !== viewEntries)) {
            val ids = ArrayList<String>(local.entries.size)
            val visited = BooleanArray(local.entries.size)
            var index = activeOrder.firstIndex
            while (index in local.entries.indices && !visited[index]) {
                visited[index] = true
                ids += local.entries[index].id
                index = activeOrder.getNextIndex(index)
            }
            if (ids.size == local.entries.size && ids != local.shuffleOrder) {
                local = local.copy(shuffleOrder = ids)
                orderChanged = true
            }
        }
        lastPlayerShuffle = activeOrder
        if (local.entries !== viewEntries || local.shuffleOrder !== viewShuffle ||
            local.index != viewIndex || mode != viewMode || owner != viewOwner) {
            val queueMode = if (mode == 1 && player.shuffleModeEnabled) 2 else mode
            upcoming = upcomingPlaybackQueue(local.entries, local.index, queueMode, local.shuffleOrder)
            viewEntries = local.entries
            viewShuffle = local.shuffleOrder
            viewIndex = local.index
            viewMode = mode
            viewOwner = owner
        }
        return orderChanged
    }

    private fun currentTrack(): Track? {
        val item = player.currentMediaItem ?: return null
        return if (owner == "music")
            local.entries.getOrNull(player.currentMediaItemIndex)
                ?.takeIf { it.id == item.mediaId }?.track
                ?: local.entries.firstOrNull { it.id == item.mediaId }?.track
                ?: item.localConfiguration?.tag as? Track
        else online
    }

    private fun item(entry: QueueEntry): MediaItem =
        MediaItem.Builder()
            .setMediaId(entry.id)
            .setUri(entry.track.uri)
            .setTag(entry.track)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.track.title.ifBlank { "Untitled" })
                    .setDisplayTitle(entry.track.title.ifBlank { "Untitled" })
                    .setSubtitle(entry.track.artist)
                    .setArtist(entry.track.artist)
                    .setAlbumTitle(entry.track.album)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(
                        entry.track.art
                            .takeIf { it.isNotBlank() }
                            ?.let { if (it.startsWith("/")) android.net.Uri.fromFile(java.io.File(it)) else android.net.Uri.parse(it) }
                    )
                    .setExtras(Bundle().apply { putString("trackId", entry.track.id) })
                    .build()
            )
            .build()

    private fun writeVolume() {
        val gain =
            songOutputGain(
                if (owner == "music") master else onlineVolume,
                previewGain?.takeIf { it.first == currentTrack()?.id }?.second
                    ?: currentTrack()?.volumePercent
                    ?: 100f,
            )
        // ExoPlayer's volume is limited to 0..1
        // apply only the excess boost to PCM, after combining both saved sliders, so attenuation doesn't clip prematurely
        eq.signalGain = gain.coerceAtLeast(1f)
        player.volume = gain.coerceAtMost(1f) * fadeGain * transitionGain
    }

    private fun resetTransitionFade() {
        transitionFadeJob?.cancel()
        transitionFadeJob = null
        if (transitionGain != 1f) {
            transitionGain = 1f
            writeVolume()
        }
    }

    private fun startTransitionFadeIn() {
        val halfFade = (crossfadeMs / 2).coerceIn(250, 4000)
        transitionFadeJob?.cancel()
        transitionGain = 0f
        writeVolume()
        transitionFadeJob =
            scope.launch {
                val start = SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - start
                    transitionGain = (elapsed.toFloat() / halfFade).coerceIn(0f, 1f)
                    writeVolume()
                    if (elapsed >= halfFade) break
                    delay(24)
                }
                transitionGain = 1f
                transitionFadeJob = null
                writeVolume()
            }
    }

    private fun updateTransitionFadeOut() {
        if (owner != "music" || crossfadeMs <= 0 || !player.isPlaying) {
            if (transitionFadeJob == null && transitionGain != 1f) resetTransitionFade()
            return
        }
        val duration = player.duration
        if (duration <= crossfadeMs) return
        val halfFade = (crossfadeMs / 2).coerceIn(250, 4000)
        val remaining = duration - player.currentPosition
        if (remaining in 1..halfFade.toLong()) {
            transitionFadeJob?.cancel()
            transitionFadeJob = null
            transitionGain = (remaining.toFloat() / halfFade).coerceIn(0f, 1f)
            writeVolume()
        } else if (transitionFadeJob == null && transitionGain != 1f) {
            transitionGain = 1f
            writeVolume()
        }
    }

    private fun applyTrack() {
        val t = currentTrack() ?: return
        applyParameters(t)
        writeVolume()
    }

    private fun applyParameters(track: Track) {
        val parameters = PlaybackParameters(track.speed, if (track.nightcore) track.speed else 1f)
        if (player.playbackParameters != parameters) player.playbackParameters = parameters
    }

    /** drain the old audio before a different song effect can reach the output */
    private fun updateTransitionBoundary() {
        val current = currentTrack()
        val next = if (owner == "music" && mode != 1)
            local.entries.getOrNull(player.nextMediaItemIndex)?.track else current
        player.setPauseAtEndOfMediaItems(owner == "music" && current != null && next != null &&
            (current.speed != next.speed || current.nightcore != next.nightcore || current.volumePercent != next.volumePercent))
    }

    private fun selectMusic(index: Int, position: Long, play: Boolean) {
        val track = local.entries.getOrNull(index)?.track ?: return
        player.pause()
        resetFade()
        applyParameters(track)
        player.seekTo(index, position)
        applyTrack()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = play
        publish()
    }

    private fun publish(error: String = "") {
        val t = currentTrack()
        val previous = state.value
        val pos = player.currentPosition.coerceAtLeast(0)
        publishProgress(pos)
        state.value =
            PlaybackState(
                owner,
                t,
                if (owner == "music") t else local.entries.getOrNull(local.index)?.track,
                online,
                player.isPlaying,
                pos,
                if (owner == "music") pos else local.position,
                if (owner == "online") pos else onlinePosition,
                player.duration.coerceAtLeast(0),
                player.playbackState == Player.STATE_BUFFERING,
                error.ifEmpty { previous.error },
                local.entries,
                local.index,
                local.context,
                g.prefs.state.value.sleepEnd,
                mode,
                upcoming,
            )
    }

    private fun publishProgress(pos: Long = player.currentPosition.coerceAtLeast(0)) {
        progress.value = PlaybackProgress(if (owner == "music") pos else local.position,
            if (owner == "online") pos else onlinePosition)
    }

    private fun resetFade() {
        fade?.cancel()
        fade = null
        fadeGain = 1f
        resetTransitionFade()
        writeVolume()
    }

    fun pause(fading: Boolean = true) {
        if (fading && g.prefs.state.value.pauseFade && player.isPlaying) {
            if (fade != null) return
            fade =
                scope.launch {
                    val duration = g.prefs.state.value.fadeMs.coerceIn(50, 5000)
                    val start = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - start < duration) {
                        fadeGain =
                            (1f - (SystemClock.elapsedRealtime() - start).toFloat() / duration)
                                .coerceIn(0f, 1f)
                        writeVolume()
                        delay(16)
                    }
                    player.pause()
                    fadeGain = 1f
                    fade = null
                    writeVolume()
                    publish()
                }
        } else {
            resetFade()
            player.pause()
            publish()
        }
    }

    fun toggleMusic() {
        if (owner != "music") {
            saveOnline()
            restoreLocal(true)
        } else if (player.playWhenReady) pause()
        else {
            resetFade()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun toggleOnline() {
        if (owner != "online") {
            online?.let { startOnline(it, onlinePosition) }
        } else if (player.playWhenReady) pause()
        else {
            resetFade()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun resumeActive() {
        resetFade()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun leaveBrowse() {
        if (owner == "online") pause(false)
    }

    private fun saveOnline() {
        if (owner == "online") {
            onlinePosition = player.currentPosition
            player.pause()
        }
    }

    private fun saveLocal() {
        if (owner == "music" && player.mediaItemCount > 0)
            local =
                local.copy(index = player.currentMediaItemIndex, position = player.currentPosition)
    }

    private fun restoreLocal(play: Boolean) {
        if (local.entries.isEmpty()) return
        state.value = state.value.copy(error = "")
        // pause callbacks checkpoint the old player queue, retain the requested selection
        val requested = local
        player.pause()
        local = requested
        resetFade()
        owner = "music"
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        )
        val index = local.index.coerceIn(local.entries.indices)
        val savedShuffle = local.shuffleOrder
        applyParameters(local.entries[index].track)
        changingQueue = true
        try {
            player.setMediaItems(local.entries.map(::item), index, local.position)
            applyShuffleOrder(savedShuffle)
        } finally { changingQueue = false }
        refreshQueueOrder()
        player.prepare()
        applyTrack()
        player.playWhenReady = play
        publish()
    }

    fun playTrack(
        track: Track,
        context: List<Track>,
        name: String = "Songs",
        selectedIndex: Int = context.indexOfFirst { it.id == track.id },
    ) {
        val selection = playableSelection(context, track, selectedIndex)
        if (selection == null) {
            g.message.value = "This song is unavailable or no longer in this playlist"
            return
        }
        val (rows, selected) = selection
        val sameContext = local.context == name && local.entries.map { it.track.id } == rows.map { it.id }
        val same = owner == "music" && currentTrack()?.id == track.id &&
            player.mediaItemCount > 0 && sameContext && local.index == selected
        if (same) {
            resetFade()
            player.play()
            return
        }
        val preserved = 0L
        saveOnline()
        if (!same) flushListening()
        val retained = if (same) player.currentMediaItem?.mediaId else null
        local =
            QueueState(
                rows.mapIndexed { i, row ->
                    QueueEntry(
                        id = if (i == selected && retained != null) retained else uuid(),
                        track = row,
                    )
                },
                selected,
                preserved,
                name,
            )
        restoreLocal(true)
        save()
    }

    fun startOnline(
        track: Track,
        position: Long = 0,
        headers: Map<String, String> = onlineHeaders,
        mime: String? = onlineMime,
    ) {
        onlineHeaders = headers
        onlineMime = mime
        httpSource.setDefaultRequestProperties(headers)
        state.value = state.value.copy(error = "")
        saveLocal()
        flushListening()
        resetFade()
        owner = "online"
        online = track
        player.setPauseAtEndOfMediaItems(false)
        player.pause()
        applyParameters(track)
        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, track.sourceProvider != "youtube-video")
        )
        onlinePosition = position
        player.setMediaItem(
            item(QueueEntry(track = track)).buildUpon().setMimeType(mime).build(),
            position,
        )
        player.prepare()
        applyTrack()
        player.play()
        refreshQueueOrder()
        publish()
    }

    fun next() {
        resetFade()
        if (owner != "music") {
            restoreLocal(false)
        }
        val next = player.nextMediaItemIndex.takeIf { it >= 0 } ?: 0
        selectMusic(next, 0, true)
    }

    fun previous() {
        resetFade()
        if (owner != "music") restoreLocal(false)
        val previous = if (player.currentPosition > 3000) player.currentMediaItemIndex
            else player.previousMediaItemIndex.takeIf { it >= 0 } ?: player.currentMediaItemIndex
        selectMusic(previous, 0, true)
    }

    fun seek(position: Long, music: Boolean = true) {
        if (music != (owner == "music")) {
            val duration =
                ((if (music) local.entries.getOrNull(local.index)?.track?.duration
                else online?.duration) ?: 0.0) * 1000
            if (duration <= 0) return
            val bounded = position.coerceIn(0, duration.toLong())
            if (music) {
                local = local.copy(position = bounded)
                save()
            } else onlinePosition = bounded
            publish()
            return
        }
        if (player.duration <= 0) return
        resetTransitionFade()
        player.seekTo(position.coerceIn(0, player.duration))
        publish()
    }

    fun volume(value: Float, online: Boolean = false, persist: Boolean = true) {
        val v = value.coerceIn(0f, 300f)
        if (online) onlineVolume = v else master = v
        writeVolume()
        if (persist)
            g.task {
                g.prefs.update { if (online) it.copy(onlineVolume = v) else it.copy(master = v) }
            }
    }

    fun previewVolume(id: String, percent: Float) {
        previewGain = id to percent
        if (owner == "music" && currentTrack()?.id == id) writeVolume()
    }

    fun previewSpeed(id: String, speed: Float, nightcore: Boolean) {
        if (owner == "music" && currentTrack()?.id == id)
            player.playbackParameters = PlaybackParameters(speed, if (nightcore) speed else 1f)
    }

    fun updateTrack(track: Track) = updateTracks(listOf(track))

    /** one queue pass per sync page instead of one full pass for each received song */
    fun updateTracks(tracks: kotlin.collections.Collection<Track>) {
        if (tracks.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        if (previewGain?.first in byId) previewGain = null
        var changed = false
        val entries = local.entries.map { entry ->
            val replacement = byId[entry.trackId]
            if (replacement != null && replacement != entry.track) {
                changed = true
                entry.copy(track = replacement)
            } else entry
        }
        if (!changed) return
        local = local.copy(entries = entries)
        if (owner == "music") {
            changingQueue = true
            try {
                entries.forEachIndexed { index, entry ->
                    if (entry.trackId in byId) player.replaceMediaItem(index, item(entry))
                }
            } finally { changingQueue = false }
        }
        refreshQueueOrder()
        if (owner == "music") {
            currentTrack()?.let { track ->
                player.playbackParameters = PlaybackParameters(track.speed, if (track.nightcore) track.speed else 1f)
                writeVolume()
            }
        }
        publish()
        save()
    }

    fun cycleMode() {
        val next = (mode + 1) % 3
        requestedMode = next
        applyPlaybackMode(next)
        publish()
        save()
        g.task { g.prefs.update { it.copy(mode = next) } }
    }

    fun addQueue(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        saveLocal()
        val playbackOrder = if (mode == 2)
            upcomingPlaybackQueue(local.entries, local.index, mode, local.shuffleOrder).map { it.id }
            else emptyList()
        val additions = tracks.map { QueueEntry(track = it) }
        val at = (if (next) local.index + 1 else local.entries.size).coerceIn(0, local.entries.size)
        val entries = local.entries.toMutableList().apply { addAll(at, additions) }
        local = local.copy(entries = entries)
        changingQueue = true
        try {
            if (owner == "music") player.addMediaItems(at, additions.map(::item))
            if (mode == 2) {
                val insert = if (next && playbackOrder.isNotEmpty()) 1 else playbackOrder.size
                val shuffled = playbackOrder.toMutableList().apply { addAll(insert, additions.map { it.id }) }
                applyShuffleOrder(shuffled)
            }
        } finally { changingQueue = false }
        refreshQueueOrder()
        publish()
        save()
    }

    fun queuePlay(occurrence: String) = queuePlay(local.entries.indexOfFirst { it.id == occurrence })

    fun removeQueue(occurrence: String) = removeQueue(local.entries.indexOfFirst { it.id == occurrence })

    fun moveUpcomingQueue(occurrence: String, target: String) {
        val fromView = upcoming.indexOfFirst { it.id == occurrence }
        val toView = upcoming.indexOfFirst { it.id == target }
        if (fromView <= 0 || toView <= 0 || fromView == toView) return
        if (mode == 2) {
            val order = upcoming.map { it.id }.toMutableList()
            order.add(toView, order.removeAt(fromView))
            applyShuffleOrder(order)
            refreshQueueOrder()
            publish()
            save()
        } else {
            val from = local.entries.indexOfFirst { it.id == occurrence }
            val targetIndex = local.entries.indexOfFirst { it.id == target }
            moveQueue(from, upcomingMoveDestination(from, targetIndex, fromView < toView))
        }
    }

    fun queuePlay(index: Int) {
        if (owner != "music") {
            saveOnline()
            restoreLocal(false)
        }
        if (index !in local.entries.indices) return
        selectMusic(index, 0, true)
    }

    fun removeQueue(index: Int) {
        if (index == local.index) return
        val entries = local.entries.toMutableList()
        if (index !in entries.indices) return
        entries.removeAt(index)
        local =
            local.copy(
                entries = entries,
                index = if (index < local.index) local.index - 1 else local.index,
            )
        if (owner == "music") player.removeMediaItem(index)
        refreshQueueOrder()
        publish()
        save()
    }

    fun moveQueue(from: Int, to: Int) {
        if (from !in local.entries.indices || to !in local.entries.indices) return
        val rows = local.entries.toMutableList()
        val current = rows.getOrNull(local.index)?.id
        rows.add(to, rows.removeAt(from))
        local =
            local.copy(
                entries = rows,
                index = rows.indexOfFirst { it.id == current }.coerceAtLeast(0),
            )
        if (owner == "music") player.moveMediaItem(from, to)
        refreshQueueOrder()
        publish()
        save()
    }

    fun remapTracks(mapping: Map<String, Track>) {
        local =
            local.copy(
                entries =
                    local.entries.map { e ->
                        mapping[e.track.id]?.let { e.copy(track = it, trackId = it.id) } ?: e
                    }
            )
        if (owner == "music")
            for ((index, entry) in local.entries.withIndex()) if (
                index != player.currentMediaItemIndex
            )
                player.replaceMediaItem(index, item(entry))
        playSession = playSession?.let { s -> mapping[s.track]?.let { s.copy(track = it.id) } ?: s }
        refreshQueueOrder()
        publish()
        save()
    }

    fun removeTracks(ids: Set<String>) {
        saveLocal()
        val current = local.entries.getOrNull(local.index)
        if (current?.track?.id !in ids) {
            for (index in local.entries.indices.reversed()) if (
                local.entries[index].track.id in ids
            )
                removeQueue(index)
            return
        }
        flushListening()
        val rows = local.entries.filter { it.track.id !in ids }
        val index = local.index.coerceAtMost((rows.size - 1).coerceAtLeast(0))
        val playing = player.playWhenReady
        local = local.copy(entries = rows, index = index, position = 0)
        if (owner == "music") {
            if (rows.isEmpty()) {
                player.stop()
                player.clearMediaItems()
            } else restoreLocal(playing)
        }
        refreshQueueOrder()
        publish()
        save()
    }

    fun clearUpcoming() {
        if (local.entries.size <= 1) return
        saveLocal()
        val currentIndex = local.index.coerceIn(local.entries.indices)
        val current = local.entries[currentIndex]
        local = local.copy(entries = listOf(current), index = 0, shuffleOrder = listOf(current.id))
        changingQueue = true
        try {
            if (owner == "music") {
                if (player.mediaItemCount > currentIndex + 1) player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
                if (currentIndex > 0) player.removeMediaItems(0, currentIndex)
            }
        } finally { changingQueue = false }
        refreshQueueOrder()
        publish()
        save()
    }

    fun sleep(minutes: Int) {
        g.task {
            g.prefs.update {
                it.copy(
                    sleepEnd =
                        if (minutes <= 0) 0
                        else System.currentTimeMillis() + minutes.coerceIn(1, 1440) * 60000L
                )
            }
        }
    }

    private fun save() {
        saveLocal()
        val snapshot = local
        pendingSave?.cancel()
        pendingSave = g.scope.launch {
            delay(250)
            if (!g.resetting.value) g.prefs.update { it.copy(queue = snapshot) }
        }
    }

    /** flush on pause/backgrounding; periodic checkpoints run only during playback */
    fun checkpoint() {
        if (!ready.isCompleted || ready.isCancelled) return
        accountListening()
        flushListening(false)
        save()
    }

    private fun startListening() {
        flushListening()
        sessionOccurrence = player.currentMediaItem?.mediaId
        val t = currentTrack() ?: return
        if (owner == "music") {
            playSession = Play(track = t.id)
            counted = 0
            countedQualified = false
            lastTick = SystemClock.elapsedRealtime()
            listeningRunning = player.isPlaying
        }
    }

    private fun accountListening() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceAtLeast(0)
        lastTick = now
        if (listeningRunning) {
            val session = playSession ?: return
            val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            playSession = advanceListening(session, delta, duration)
        }
    }

    private fun tick() {
        accountListening()
        updateTransitionFadeOut()
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave >= 15000) {
            lastSave = now
            flushListening(false)
            save()
        }
        // only seek/time controls observe position ticks, no whole-screen invalidation
        if (g.foreground.value) publishProgress()
    }

    private fun flushListening(clear: Boolean = true) {
        accountListening()
        val session = playSession
        if (session != null && session.heardMs > counted) {
            val added = session.heardMs - counted
            val first = session.qualified && !countedQualified
            if (first) countedQualified = true
            counted = session.heardMs
            g.task {
                g.db.withTransaction {
                    if (g.resetting.value || g.dao.track(session.track) == null)
                        return@withTransaction
                    val old =
                        g.dao.counter(g.prefs.deviceId, session.track)
                            ?: Counter(g.prefs.deviceId, session.track)
                    val value =
                        old.copy(
                            milliseconds = old.milliseconds + added,
                            plays = old.plays + if (first) 1 else 0,
                            lastPlayed = if (session.qualified) session.playedAt else old.lastPlayed,
                        )
                    g.dao.putCounter(value)
                    g.dao.putPlay(session)
                    g.library.journal(
                        "counters",
                        value.device + ":" + value.component,
                        JsonCodec.encodeToJsonElement(value),
                    )
                    if (session.qualified)
                        g.library.journal(
                            "plays",
                            session.session,
                            JsonCodec.encodeToJsonElement(session),
                        )
                }
            }
        }
        if (clear) {
            playSession = null
            counted = 0
            listeningRunning = false
        }
    }

    fun resetLibrary() {
        pendingSave?.cancel()
        playSession = null
        counted = 0
        sessionOccurrence = null
        local = QueueState()
        online = null
        onlinePosition = 0
        owner = "music"
        resetFade()
        player.stop()
        player.clearMediaItems()
        refreshQueueOrder()
        publish()
    }

    fun resumptionItems(): List<MediaItem> = local.entries.map(::item)

    fun close() {
        flushListening()
        save()
        scope.cancel()
        player.release()
    }
}
