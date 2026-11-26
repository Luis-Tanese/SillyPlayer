package dev.sillyplayer.ui

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Playback queue",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${state.context} · ${rows.size} ${if (rows.size == 1) "song" else "songs"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ToolPillButton("Clear upcoming", "trash-2", enabled = state.queue.size > 1) {
                    engine?.clearUpcoming()
                }
            }
            ModalSectionCard {
                Text(
                    when (state.mode) {
                        1 -> "Repeat one · the current song repeats. Other queued songs are kept."
                        2 -> "Shuffle · shown in playback order"
                        else -> "Repeat queue · shown in upcoming order"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f), state = queueListState, contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(rows, key = { _, e -> e.id }) { index, entry ->
                    val dragging = reorderState.draggedKey == entry.id
                    val isCurrent = entry.id == currentId
                    Row(
                        Modifier.fillMaxWidth()
                            .draggableRow(reorderState, entry.id)
                            .background(
                                when {
                                    dragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { engine?.queuePlay(entry.id) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCurrent) Spacer(Modifier.width(38.dp))
                        else DragReorderHandle(reorderState, entry.id, index)
                        Artwork(entry.track.art, 40)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (isCurrent) "Playing now · " + entry.track.artist else entry.track.artist,
                                fontSize = 12.sp,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconAction("x", "Remove from queue", !isCurrent) {
                            engine?.removeQueue(entry.id)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepDialog(g: Graph, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("30") }
    val engine by g.engine.collectAsStateWithLifecycle()
    AppModalDialog(
        onDismissRequest = onDismiss,
        title = "Sleep timer",
        subtitle = "Pause playback automatically",
        icon = "clock",
        actions = {
            ModalTonalButton("Turn off") {
                engine?.sleep(0)
                onDismiss()
            }
            ModalTonalButton("Cancel", onClick = onDismiss)
            ModalPrimaryButton("Start", icon = "check", enabled = minutes.toIntOrNull() in 1..1440) {
                engine?.sleep(minutes.toInt())
                onDismiss()
            }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in listOf(15, 30, 45, 60)) {
                ToolPillButton("${n}m", active = minutes == n.toString()) { minutes = n.toString() }
            }
        }
        Input(minutes, { minutes = it }, "Minutes (1–1440)")
    }
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
        "add" ->
            PlaylistChooser(g, listOf(track.id), onDismiss) { id ->
                onDismiss()
                g.task {
                    val res = g.library.addToCollection(id, listOf(track.id))
                    val target = if (id == "liked") "Liked Songs" else "playlist"
                    g.message.value =
                        if (res.added > 0) "Added to $target"
                        else "Already in $target (skipped)"
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
            AppModalDialog(
                onDismissRequest = onDismiss,
                title = "Source link",
                subtitle = track.title,
                icon = "external-link",
                actions = {
                    ModalTonalButton("Close", onClick = onDismiss)
                    ModalPrimaryButton("Share", icon = "external-link", enabled = track.sourceUrl.startsWith("http")) {
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
            ) {
                Text(
                    track.sourceUrl.ifBlank { "No source link is saved for this song" },
                    fontSize = 14.sp,
                    color = if (track.sourceUrl.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        "info" ->
            AppModalDialog(
                onDismissRequest = onDismiss,
                title = "Song information",
                subtitle = track.title,
                icon = "info",
                actions = { ModalPrimaryButton("Close", onClick = onDismiss) },
            ) {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        ).filter { it.second.isNotBlank() }) {
                        ModalSectionCard {
                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModifierDialog(g: Graph, track: Track, volume: Boolean, onDismiss: () -> Unit) {
    var value by
        remember(track.id, volume) { mutableFloatStateOf(if (volume) track.volumePercent else track.speed) }
    var pitch by remember(track.id) { mutableStateOf(track.nightcore) }
    // key number on track.id and mode too so switching songs doesnt leave stale text in the box
    var number by
        remember(track.id, volume) {
            mutableStateOf(
                if (volume) track.volumePercent.roundToInt().toString()
                else "%.2f".format(java.util.Locale.ROOT, track.speed)
            )
        }
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
    AppModalDialog(
        onDismissRequest = ::dismiss,
        title = if (volume) "Song volume" else "Song playback speed",
        subtitle = track.title,
        icon = if (volume) "volume-2" else "play",
        actions = {
            ModalTonalButton(if (volume) "Reset 100%" else "Reset 1.00×", icon = "rotate-ccw") {
                value = if (volume) 100f else 1f
                number = value.toString()
                pitch = false
                changed = true
                commit()
            }
            ModalPrimaryButton("Done", icon = "check", onClick = ::dismiss)
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in
                    if (volume) listOf(50f, 75f, 100f, 125f, 150f, 200f, 250f, 300f)
                    else listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) {
                    val active = kotlin.math.abs(value - n) < 0.01f
                    ToolPillButton(if (volume) "${n.toInt()}%" else "%.2f×".format(n), active = active) {
                        value = n
                        number = if (volume) n.toInt().toString() else n.toString()
                        changed = true
                        commit()
                    }
                }
            }
            ModalSectionCard {
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
            }
            Hint("Saved for this song only")
            if (volume)
                Hint(
                    "Main ${prefs.master.toInt()}% · Signal ${(songOutputGain(prefs.master,value) * 100).toInt()}%\nSignal caps at 300%; boost can distort peaks. Both settings keep their values."
                )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerDialog(g: Graph, onDismiss: () -> Unit) {
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val builtins =
        linkedMapOf(
            "Flat" to List(10) { 0f },
            "Bass Boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Rock" to listOf(4f, 3f, 1f, -1f, -2f, 1f, 3f, 4f, 4f, 3f),
            "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f, 0f, 1f),
            "Classical" to listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 2f, 3f, 3f),
        )
    val customPresets =
        remember(prefs.eqPresets) {
            prefs.eqPresets.filter { !it.deleted && it.name.isNotBlank() }.sortedBy { it.name.lowercase() }
        }
    val allPresetNames =
        remember(customPresets) { builtins.keys.toList() + customPresets.map { it.name } + "Custom" }
    val activeCustom =
        remember(prefs.eqPreset, customPresets) {
            customPresets.firstOrNull { it.name.equals(prefs.eqPreset, ignoreCase = true) }
        }
    // always normalize to 10 bands so slider writes cant crash if saved prefs ever had fewer entries
    var gains by remember(prefs.eq) { mutableStateOf(normalizeEqGains(prefs.eq)) }
    var enabled by remember(prefs.eqEnabled) { mutableStateOf(prefs.eqEnabled) }
    var savingPreset by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetError by remember { mutableStateOf("") }
    fun apply(name: String = "Custom") {
        val copy = normalizeEqGains(gains)
        val on = enabled
        val now = System.currentTimeMillis()
        g.task {
            g.prefs.update {
                it.copy(
                    eq = copy,
                    eqEnabled = on,
                    eqPreset = name,
                    eqStateUpdatedAt = maxOf(now, it.eqStateUpdatedAt + 1),
                )
            }
        }
    }
    fun saveCustomPreset() {
        val trimmed = presetName.trim()
        if (trimmed.isEmpty()) {
            presetError = "Enter a preset name"
            return
        }
        val reserved = builtins.keys + "Custom"
        if (reserved.any { it.equals(trimmed, ignoreCase = true) }) {
            presetError = "Choose a name different from built-in presets"
            return
        }
        val normalized = normalizeEqGains(gains)
        val now = System.currentTimeMillis()
        savingPreset = false
        presetError = ""
        g.task {
            g.prefs.update { p ->
                val matching = p.eqPresets.filter { it.name.equals(trimmed, ignoreCase = true) }
                val existingActive = matching.firstOrNull { !it.deleted }
                // stay strictly newer than any prior revision so slight clock skew with desktop cant drop local saves
                val prevMax = matching.maxOfOrNull { it.updatedAt } ?: 0L
                val stamp = maxOf(now, prevMax + 1)
                val entry =
                    EqPreset(
                        id = existingActive?.id ?: uuid(),
                        name = trimmed,
                        gains = normalized,
                        updatedAt = stamp,
                        deleted = false,
                    )
                p.copy(
                    eq = normalized,
                    eqEnabled = enabled,
                    eqPreset = trimmed,
                    eqPresets = mergeEqPresets(p.eqPresets, listOf(entry)),
                    eqStateUpdatedAt = maxOf(stamp, p.eqStateUpdatedAt + 1),
                )
            }
        }
    }
    fun deleteCustomPreset(preset: EqPreset) {
        val now = System.currentTimeMillis()
        g.task {
            g.prefs.update { p ->
                val prevMax =
                    p.eqPresets
                        .filter { it.id == preset.id || it.name.equals(preset.name, ignoreCase = true) }
                        .maxOfOrNull { it.updatedAt } ?: preset.updatedAt
                // keep tombstone strictly newer so local deletes stick even under clock drift
                val stamp = maxOf(now, prevMax + 1)
                val tombstone = preset.copy(gains = emptyList(), updatedAt = stamp, deleted = true)
                val activeRemoved = p.eqPreset.equals(preset.name, ignoreCase = true)
                val nextName = if (activeRemoved) "Custom" else p.eqPreset
                p.copy(
                    eqPreset = nextName,
                    eqPresets = mergeEqPresets(p.eqPresets, listOf(tombstone)),
                    eqStateUpdatedAt = if (activeRemoved) maxOf(stamp, p.eqStateUpdatedAt + 1) else p.eqStateUpdatedAt,
                )
            }
        }
    }
    AppModalDialog(
        onDismissRequest = onDismiss,
        title = "Equalizer",
        subtitle = if (enabled) "Active · ${prefs.eqPreset}" else "Disabled",
        icon = "settings",
        actions = {
            ModalTonalButton("Reset", icon = "rotate-ccw") {
                gains = List(10) { 0f }
                apply("Flat")
            }
            ModalPrimaryButton("Done", icon = "check", onClick = onDismiss)
        },
    ) {
        ModalSectionCard {
            Choice("Enable equalizer", enabled) {
                enabled = it
                apply(prefs.eqPreset)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // match case-insensitively like activeCustom does so capitalization tweaks dont show "Custom"
                PickMenu(
                    allPresetNames.firstOrNull { it.equals(prefs.eqPreset, ignoreCase = true) } ?: "Custom",
                    allPresetNames,
                ) { name ->
                    if (name in builtins) {
                        gains = builtins.getValue(name)
                        apply(name)
                    } else {
                        customPresets.firstOrNull { it.name == name }?.let { saved ->
                            gains = normalizeEqGains(saved.gains)
                            apply(saved.name)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                ToolPillButton(if (activeCustom != null) "Update" else "Save preset", "plus") {
                    presetName = activeCustom?.name.orEmpty()
                    presetError = ""
                    savingPreset = true
                }
                if (activeCustom != null) {
                    IconAction("trash-2", "Delete saved preset", tint = MaterialTheme.colorScheme.error) {
                        deleteCustomPreset(activeCustom)
                    }
                }
            }
            if (savingPreset) {
                ModalSectionCard(highlighted = true) {
                    Input(presetName, { presetName = it; presetError = "" }, "Preset name (syncs with desktop)")
                    if (presetError.isNotBlank()) Text(presetError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        ModalTonalButton("Cancel") { savingPreset = false; presetError = "" }
                        ModalPrimaryButton("Save", icon = "check", enabled = presetName.isNotBlank()) { saveCustomPreset() }
                    }
                }
            }
        }
        ModalSectionCard {
            Column(Modifier.heightIn(max = 310.dp).verticalScroll(rememberScrollState())) {
                for ((i, f) in
                    listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
                        .withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f, Modifier.width(54.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        gains.getOrElse(i) { 0f },
                        { n -> gains = gains.toMutableList().also { it[i] = n.roundToInt().toFloat() } },
                        Modifier.weight(1f),
                        valueRange = -12f..12f,
                        steps = 23,
                        onValueChangeFinished = { apply() },
                    )
                    Text(
                        "${gains.getOrElse(i){0f}.toInt()} dB",
                        Modifier.width(42.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Hint("Automatic preamp reduction limits clipping.")
    }
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
    AppModalDialog(
        onDismissRequest = onDismiss,
        title = "Edit metadata",
        subtitle = track.title,
        icon = "pencil",
        actions = {
            ModalTonalButton("Cancel", onClick = onDismiss)
            ModalPrimaryButton("Save", icon = "check") {
                g.task { g.library.editTags(track.id, title, artist, album, albumArtist, cover) }
                onDismiss()
            }
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Input(title, { title = it }, "Title")
            val cleanedTitle = remember(title) { cleanSongTitle(title) }
            if (cleanedTitle != title) {
                ModalOptionRow("Clean title", cleanedTitle, icon = "check", selected = true) { title = cleanedTitle }
            }
            Input(artist, { artist = it }, "Artist / creator")
            Input(album, { album = it }, "Album")
            Input(albumArtist, { albumArtist = it }, "Album artist")
            ModalOptionRow(
                title = if (cover.isNotBlank()) "Artwork selected" else "Change cover artwork",
                subtitle = if (cover.isNotBlank()) "Tap to choose another image" else "Pick an image from device storage",
                icon = "image",
                selected = cover.isNotBlank(),
            ) {
                pickers.image { cover = it }
            }
            ModalOptionRow(
                title = if (searching) "Searching MusicBrainz…" else "Look up metadata",
                subtitle = "Match title and artist online",
                icon = "search",
            ) {
                if (!searching) {
                    searching = true
                    g.task {
                        try {
                            candidates = g.online.lookupMetadata(title, artist)
                        } finally {
                            searching = false
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                ModalSectionCard {
                    for (c in candidates) {
                        ModalOptionRow(c.first, c.second, icon = "music-2") {
                            title = c.first
                            artist = c.second
                            candidates = emptyList()
                        }
                    }
                }
            }
            Hint("Writes embedded tags when the file and provider are writable.")
        }
    }
}
