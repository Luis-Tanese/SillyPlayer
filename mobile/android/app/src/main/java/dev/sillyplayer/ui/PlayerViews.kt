package dev.sillyplayer.ui

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import dev.sillyplayer.media.*
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PlaybackPosition(g: Graph, online: Boolean = false, content: @Composable (Long) -> Unit) {
    val engine by g.engine.collectAsStateWithLifecycle()
    val fallback = remember { MutableStateFlow(PlaybackProgress()) }
    val progress by (engine?.progress ?: fallback).collectAsStateWithLifecycle()
    content(if (online) progress.onlinePosition else progress.musicPosition)
}

@Composable
private fun SleepTimerAction(end: Long, onClick: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var remaining by remember(end) { mutableLongStateOf((end-System.currentTimeMillis()).coerceAtLeast(0)) }
    LaunchedEffect(end, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                remaining=(end-System.currentTimeMillis()).coerceAtLeast(0)
                if(remaining==0L) break
                delay(1000)
            }
        }
    }
    Action("Sleep timer · " + time(remaining), "clock", onClick=onClick)
}

@Composable
private fun SongLikeButton(g: Graph, id: String?) {
    val changes =
        remember(g, id) {
            if (id.isNullOrEmpty()) kotlinx.coroutines.flow.flowOf(false)
            else g.dao.observeLiked(id).distinctUntilChanged()
        }
    val liked by changes.collectAsStateWithLifecycle(false)
    IconAction(
        icon = if (liked) "heart-filled" else "heart",
        label = if (liked) "Unlike" else "Like",
        enabled = id != null,
        tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
    ) {
        id?.let { g.task { g.library.like(it) } }
    }
}

@Composable
fun CompactPlayer(
    g: Graph,
    state: PlaybackState,
    track: Track?,
    onExpand: () -> Unit,
    onQueue: () -> Unit,
) {
    val engine by g.engine.collectAsStateWithLifecycle()
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val visualShift by animateFloatAsState(dragX.coerceIn(-120f, 120f), label = "compactSwipe")
    val latestTrack by rememberUpdatedState(track)
    val latestEngine by rememberUpdatedState(engine)
    val latestExpand by rememberUpdatedState(onExpand)
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragX = 0f
                            dragY = 0f
                        },
                        onDragCancel = {
                            dragX = 0f
                            dragY = 0f
                        },
                        onDragEnd = {
                            val horizontalThreshold = 68.dp.toPx()
                            val verticalThreshold = 44.dp.toPx()
                            if (latestTrack != null) {
                                when {
                                    dragY < -verticalThreshold && kotlin.math.abs(dragY) > kotlin.math.abs(dragX) ->
                                        latestExpand()
                                    dragX < -horizontalThreshold && kotlin.math.abs(dragX) > kotlin.math.abs(dragY) ->
                                        latestEngine?.next()
                                    dragX > horizontalThreshold && kotlin.math.abs(dragX) > kotlin.math.abs(dragY) ->
                                        latestEngine?.previous()
                                }
                            }
                            dragX = 0f
                            dragY = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragX += dragAmount.x
                        dragY += dragAmount.y
                    }
                }
                .graphicsLayer { translationX = visualShift * 0.35f }
                .padding(start = 10.dp, end = 3.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                track?.art ?: "",
                48,
                Modifier.clickable(enabled = track != null) { onExpand() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Choose a song",
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.let {
                        it.artist +
                            modifierLabel(it)
                                .takeIf { label -> label.isNotBlank() }
                                ?.let { label -> " · $label" }
                                .orEmpty()
                    } ?: "Your local library",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track != null)
                    Text(
                        "From: " + state.context,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            IconButton({ engine?.previous() }, Modifier.size(36.dp), enabled = track != null) {
                Glyph("skip-back", "Previous", Modifier.size(20.dp))
            }
            IconButton({ engine?.toggleMusic() }, Modifier.size(42.dp), enabled = track != null) {
                Glyph(
                    if (state.owner == "music" && state.playing) "pause" else "play",
                    "Play / pause",
                    Modifier.size(24.dp),
                )
            }
            IconButton({ engine?.next() }, Modifier.size(36.dp), enabled = track != null) {
                Glyph("skip-forward", "Next", Modifier.size(20.dp))
            }
            IconButton(onQueue, Modifier.size(36.dp)) {
                Glyph("list-music", "Queue", Modifier.size(20.dp))
            }
            IconButton({ engine?.cycleMode() }, Modifier.size(32.dp)) {
                Glyph(
                    when (state.mode) {
                        1 -> "repeat-1"
                        2 -> "shuffle"
                        else -> "repeat"
                    },
                    when (state.mode) {
                        1 -> "Repeat one"
                        2 -> "Shuffle"
                        else -> "Repeat queue"
                    },
                    Modifier.size(20.dp),
                    tint = if (state.mode != 0) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
        PlaybackPosition(g) { position ->
            SeekLine(
                position,
                if (state.owner == "music") state.duration
                else ((track?.duration ?: 0.0) * 1000).toLong(),
                track?.id ?: "",
                flushTop = true,
            ) {
                engine?.seek(it)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandedPlayer(
    g: Graph,
    state: PlaybackState,
    track: Track?,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onAction: (Track, String) -> Unit,
) {
    val engine by g.engine.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    val duration =
        if (state.owner == "music") state.duration else ((track?.duration ?: 0.0) * 1000).toLong()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconAction("arrow-left", "Back") { onBack() }
            Spacer(Modifier.weight(1f))
            IconAction("list-music", "Queue") { onQueue() }
            Box {
                IconAction("ellipsis", "Player actions") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Sleep timer") },
                        leadingIcon = { Glyph("clock") },
                        onClick = {
                            sleep = true
                            menu = false
                        },
                    )
                    for ((label, action, icon) in
                        listOf(
                            Triple("Playback speed", "speed", "play"),
                            Triple("Song volume", "volume", "volume-2"),
                            Triple("Equalizer", "eq", "settings"),
                            Triple("Add to playlist", "add", "list-music"),
                            Triple("Edit metadata", "metadata", "pencil"),
                            Triple("Information", "info", "info"),
                            Triple("Link", "link", "external-link"),
                        )) DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { Glyph(icon) },
                        onClick = {
                            menu = false
                            track?.let { onAction(it, action) }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth(.82f).heightIn(max = 320.dp).aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Artwork(track?.art ?: "", 320, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(22.dp))
        Text(
            track?.title ?: "Choose a song",
            fontSize = 22.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track?.artist ?: "",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        track?.let { Hint(modifierLabel(it)) }
        Spacer(Modifier.height(20.dp))
        PlaybackPosition(g) { position ->
            SeekLine(position, duration, track?.id ?: "") { engine?.seek(it) }
            Row(Modifier.fillMaxWidth()) {
                Text(time(position), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (duration > 0) time(duration) else "Duration unavailable",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconAction(
                when (state.mode) {
                    1 -> "repeat-1"
                    2 -> "shuffle"
                    else -> "repeat"
                },
                when (state.mode) {
                    1 -> "Repeat one"
                    2 -> "Shuffle"
                    else -> "Repeat queue"
                },
                tint = if (state.mode != 0) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            ) {
                engine?.cycleMode()
            }
            IconAction("skip-back", "Previous", track != null) { engine?.previous() }
            IconButton({ engine?.toggleMusic() }, Modifier.size(64.dp), enabled = track != null) {
                Glyph(
                    if (state.owner == "music" && state.playing) "pause" else "play",
                    "Play / pause",
                    Modifier.size(40.dp),
                )
            }
            IconAction("skip-forward", "Next", track != null) { engine?.next() }
            SongLikeButton(g,track?.id)
        }
        Hint(
            when (state.mode) {
                1 -> "Repeat one"
                2 -> "Shuffle"
                else -> "Repeat queue"
            }
        )
        MasterVolume(g, false)
        if (state.owner == "online") Hint("Local music paused · Browse audio is playing")
        if (state.error.isNotBlank()) Hint(state.error)
        if (state.sleepEnd > 0)
            SleepTimerAction(state.sleepEnd) {
                sleep = true
            }
        Spacer(Modifier.height(15.dp))
    }
    if (sleep) SleepDialog(g) { sleep = false }
}

@Composable
fun MasterVolume(g: Graph, online: Boolean) {
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val engine by g.engine.collectAsStateWithLifecycle()
    val actual = if (online) prefs.onlineVolume else prefs.master
    var draft by remember(online) { mutableFloatStateOf(actual) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(actual) { if (!dragging) draft = actual }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (draft <= 0) "volume-x" else "volume-2")
            Spacer(Modifier.width(8.dp))
            Text("Volume ${draft.roundToInt()}%", fontSize = 13.sp)
        }
        Slider(
            draft,
            {
                draft = it.roundToInt().toFloat()
                dragging = true
                engine?.volume(draft, online, false)
            },
            valueRange = 0f..300f,
            steps = 299,
            onValueChangeFinished = {
                dragging = false
                if (engine != null) engine?.volume(draft, online)
                else
                    g.task {
                        g.prefs.update {
                            if (online) it.copy(onlineVolume = draft) else it.copy(master = draft)
                        }
                    }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuePanel(g: Graph, state: PlaybackState, onDismiss: () -> Unit) {
    val engine by g.engine.collectAsStateWithLifecycle()
    val rows = state.upcomingQueue
    val currentId = state.queue.getOrNull(state.index)?.id
    val queueListState = rememberLazyListState()
    val queueScope = rememberCoroutineScope()
    val latestRows by rememberUpdatedState(rows)
    val latestEngine by rememberUpdatedState(engine)
    val reorderState =
        remember(queueListState, queueScope, currentId) {
            DragReorderState(
                listState = queueListState,
                scope = queueScope,
                canSwapTo = { idx -> latestRows.getOrNull(idx)?.id?.let { it != currentId } == true },
                onMove = { from, to ->
                    val source = latestRows.getOrNull(from) ?: return@DragReorderState
                    val target = latestRows.getOrNull(to) ?: return@DragReorderState
                    latestEngine?.moveUpcomingQueue(source.id, target.id)
                },
            )
        }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Queue · " + state.context,
                    Modifier.weight(1f),
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Action("Clear upcoming", enabled = state.queue.size > 1) { engine?.clearUpcoming() }
            }
            Hint(when (state.mode) {
                1 -> "Repeat one · the current song repeats. Other queued songs are kept."
                2 -> "Shuffle · shown in playback order"
                else -> "Repeat queue · shown in upcoming order"
            })
            LazyColumn(state = queueListState) {
                itemsIndexed(rows, key = { _, e -> e.id }) { index, entry ->
                    val dragging = reorderState.draggedKey == entry.id
                    Row(
                        Modifier.fillMaxWidth()
                            .draggableRow(reorderState, entry.id)
                            .background(
                                if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { engine?.queuePlay(entry.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.id == currentId) Spacer(Modifier.width(38.dp))
                        else DragReorderHandle(reorderState, entry.id, index)
                        Artwork(entry.track.art, 40)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (entry.id == currentId) "Current · " + entry.track.artist
                                else entry.track.artist,
                                fontSize = 12.sp,
                                color =
                                    if (entry.id == currentId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconAction("x", "Remove from queue", entry.id != currentId) {
                            engine?.removeQueue(entry.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepDialog(g: Graph, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("30") }
    val engine by g.engine.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                Row { for (n in listOf(15, 30, 60)) Action("${n}m") { minutes = n.toString() } }
                Input(minutes, { minutes = it }, "Minutes (1–1440)")
                Action("Cancel timer") {
                    engine?.sleep(0)
                    onDismiss()
                }
            }
        },
        confirmButton = {
            Action("Start", enabled = minutes.toIntOrNull() in 1..1440) {
                engine?.sleep(minutes.toInt())
                onDismiss()
            }
        },
        dismissButton = { Action("Close") { onDismiss() } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongActionDialog(
    g: Graph,
    pickers: Pickers,
    track: Track,
    action: String,
    onDismiss: () -> Unit,
) {
    var duplicate by remember { mutableStateOf<String?>(null) }
    when (action) {
        "next",
        "like" ->
            LaunchedEffect(track.id, action) {
                if (action == "next") g.engine.value?.addQueue(listOf(track), true)
                else g.task { g.library.like(track.id) }
                onDismiss()
            }
        "speed" -> ModifierDialog(g, track, false, onDismiss)
        "volume" -> ModifierDialog(g, track, true, onDismiss)
        "eq" -> EqualizerDialog(g, onDismiss)
        "add" -> {
            PlaylistChooser(g, listOf(track.id), onDismiss) { id, allow ->
                g.task {
                    if (g.library.add(id, listOf(track.id), allow)) onDismiss() else duplicate = id
                }
            }
            duplicate?.let { id ->
                Confirmation(
                    "Song already in playlist",
                    "Add another occurrence?",
                    "Add again",
                    { duplicate = null },
                ) {
                    g.task { g.library.add(id, listOf(track.id), true) }
                    onDismiss()
                }
            }
        }
        "trash" ->
            Confirmation(
                if (track.available && track.uri.isNotBlank()) "Move song to Trash?" else "Remove song from library?",
                if (track.available && track.uri.isNotBlank()) "${track.title}\n\nThe file remains recoverable until Trash is emptied."
                else "${track.title}\n\nThis removes its library entry and syncs the removal. No audio file is deleted on this device.",
                if (track.available && track.uri.isNotBlank()) "Move to Trash" else "Remove entry",
                onDismiss,
            ) {
                g.task { g.library.trash(listOf(track.id)) }
            }
        "metadata" -> MetadataDialog(g, pickers, track, onDismiss)
        "link" ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Link") },
                text = {
                    Text(track.sourceUrl.ifBlank { "No source link is saved for this song" })
                },
                confirmButton = {
                    Action("Share", enabled = track.sourceUrl.startsWith("http")) {
                        g.context.startActivity(
                            Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, track.sourceUrl)
                                    },
                                    "Share song link",
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                dismissButton = { Action("Close") { onDismiss() } },
            )
        "info" ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Information") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for ((label, value) in
                            listOf(
                                "Title" to track.title,
                                "Artist" to track.artist,
                                "Album" to track.album,
                                "Album artist" to track.albumArtist,
                                "Duration" to time((track.duration * 1000).toLong()),
                                "Source uploader" to track.uploader,
                                "Source title" to track.videoTitle,
                                "Source URL" to track.sourceUrl,
                                "File" to track.uri,
                                "Song ID" to track.id,
                                "Modifiers" to modifierLabel(track),
                            )) Text(
                            "$label: $value",
                            Modifier.padding(vertical = 4.dp),
                            fontSize = 13.sp,
                        )
                    }
                },
                confirmButton = { Action("Close") { onDismiss() } },
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModifierDialog(g: Graph, track: Track, volume: Boolean, onDismiss: () -> Unit) {
    var value by
        remember(track.id) { mutableFloatStateOf(if (volume) track.volumePercent else track.speed) }
    var pitch by remember(track.id) { mutableStateOf(track.nightcore) }
    var number by remember { mutableStateOf(value.toString()) }
    var changed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    fun commit() {
        preview?.cancel()
        if (changed) {
            val v = value
            val p = pitch
            g.task {
                g.library.modifiers(
                    track.id,
                    speed = if (volume) null else v,
                    nightcore = if (volume) null else p,
                    volume = if (volume) v else null,
                )
            }
            changed = false
        }
    }
    fun dismiss() {
        commit()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(if (volume) "Song volume" else "Song playback speed") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Hint(track.title)
                FlowRow {
                    for (n in
                        if (volume) listOf(50f, 75f, 100f, 125f, 150f, 200f, 250f, 300f)
                        else listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) Action(
                        if (volume) "${n.toInt()}%" else "%.2f×".format(n)
                    ) {
                        value = n
                        number = if (volume) n.toInt().toString() else n.toString()
                        changed = true
                        commit()
                    }
                }
                Slider(
                    value,
                    {
                        value =
                            if (volume) it.roundToInt().toFloat()
                            else (it * 100).roundToInt() / 100f
                        number =
                            if (volume) value.toInt().toString()
                            else "%.2f".format(java.util.Locale.ROOT, value)
                        changed = true
                        if (preview?.isActive != true)
                            preview =
                                scope.launch {
                                    delay(64)
                                    if (volume) g.engine.value?.previewVolume(track.id, value)
                                    else g.engine.value?.previewSpeed(track.id, value, pitch)
                                }
                    },
                    valueRange = if (volume) 0f..300f else .5f..2f,
                    steps = if (volume) 299 else 149,
                    onValueChangeFinished = ::commit,
                )
                Input(
                    number,
                    {
                        number = it
                        val n = it.toFloatOrNull()
                        if (n != null && n.isFinite() && n in if (volume) 0f..300f else .5f..2f) {
                            value = n
                            changed = true
                        }
                    },
                    if (volume) "Song volume percent" else "Speed (0.50–2.00×)",
                )
                if (!volume)
                    Choice("${pitchEffectLabel(value)} · pitch follows speed", pitch) {
                        pitch = it
                        changed = true
                        commit()
                    }
                Action(if (volume) "Reset to 100%" else "Reset to 1.00×", "rotate-ccw") {
                    value = if (volume) 100f else 1f
                    number = value.toString()
                    pitch = false
                    changed = true
                    commit()
                }
                Hint("Saved for this song only")
                if (volume)
                    Hint(
                        "Main ${prefs.master.toInt()}% · Signal ${(songOutputGain(prefs.master,value) * 100).toInt()}%\nSignal caps at 300%; boost can distort peaks. Both settings keep their values."
                    )
            }
        },
        confirmButton = { Action("Close") { dismiss() } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerDialog(g: Graph, onDismiss: () -> Unit) {
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val presets =
        linkedMapOf(
            "Flat" to List(10) { 0f },
            "Bass Boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Rock" to listOf(4f, 3f, 1f, -1f, -2f, 1f, 3f, 4f, 4f, 3f),
            "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f, 0f, 1f),
            "Classical" to listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 2f, 3f, 3f),
        )
    var gains by remember { mutableStateOf(prefs.eq) }
    var enabled by remember { mutableStateOf(prefs.eqEnabled) }
    fun apply(name: String = "Custom") {
        val copy = gains
        val on = enabled
        g.task { g.prefs.update { it.copy(eq = copy, eqEnabled = on, eqPreset = name) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Equalizer") },
        text = {
            Column {
                Choice("Enable", enabled) {
                    enabled = it
                    apply()
                }
                PickMenu(prefs.eqPreset, presets.keys.toList() + "Custom") { name ->
                    if (name in presets) {
                        gains = presets.getValue(name)
                        apply(name)
                    }
                }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    for ((i, f) in
                        listOf(
                                "31 Hz",
                                "62 Hz",
                                "125 Hz",
                                "250 Hz",
                                "500 Hz",
                                "1 kHz",
                                "2 kHz",
                                "4 kHz",
                                "8 kHz",
                                "16 kHz",
                            )
                            .withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, Modifier.width(58.dp), fontSize = 11.sp)
                        Slider(
                            gains.getOrElse(i) { 0f },
                            { n ->
                                gains =
                                    gains.toMutableList().also { it[i] = n.roundToInt().toFloat() }
                            },
                            Modifier.weight(1f),
                            valueRange = -12f..12f,
                            steps = 23,
                            onValueChangeFinished = { apply() },
                        )
                        Text(
                            "${gains.getOrElse(i){0f}.toInt()} dB",
                            Modifier.width(42.dp),
                            fontSize = 11.sp,
                        )
                    }
                }
                Action("Reset", "rotate-ccw") {
                    gains = List(10) { 0f }
                    apply("Flat")
                }
                Hint("Automatic preamp reduction limits clipping.")
            }
        },
        confirmButton = { Action("Close") { onDismiss() } },
    )
}

@Composable
private fun MetadataDialog(g: Graph, pickers: Pickers, track: Track, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var albumArtist by remember { mutableStateOf(track.albumArtist) }
    var cover by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Input(title, { title = it }, "Title")
                val cleanedTitle = remember(title) { cleanSongTitle(title) }
                if (cleanedTitle != title) {
                    Action("Clean title → $cleanedTitle", "check") { title = cleanedTitle }
                }
                Input(artist, { artist = it }, "Artist / creator")
                Input(album, { album = it }, "Album")
                Input(albumArtist, { albumArtist = it }, "Album artist")
                Action("Change cover", "image") { pickers.image { cover = it } }
                Action(if (searching) "Searching…" else "Look up metadata", "search", !searching) {
                    searching = true
                    g.task {
                        try {
                            candidates = g.online.lookupMetadata(title, artist)
                        } finally {
                            searching = false
                        }
                    }
                }
                for (c in candidates) TextButton({
                    title = c.first
                    artist = c.second
                    candidates = emptyList()
                }) {
                    Text(c.first + " · " + c.second)
                }
                Hint("Writes embedded tags when the file and provider are writable.")
            }
        },
        confirmButton = {
            Action("Save") {
                g.task { g.library.editTags(track.id, title, artist, album, albumArtist, cover) }
                onDismiss()
            }
        },
        dismissButton = { Action("Cancel") { onDismiss() } },
    )
}
