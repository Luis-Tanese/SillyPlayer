package dev.sillyplayer.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.sillyplayer.data.Track
import kotlin.math.roundToLong
import dev.sillyplayer.media.pitchEffectLabel
import dev.sillyplayer.online.thumbnailCandidates

fun time(milliseconds: Long): String {
    if (milliseconds < 0) return "—"
    val s = milliseconds / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun modifierLabel(track: Track): String =
    buildList {
            if (track.speed != 1f || track.nightcore)
                add("%.2f×%s".format(track.speed, if (track.nightcore) " ${pitchEffectLabel(track.speed)}" else ""))
            if (track.volumePercent != 100f) add("Vol ${track.volumePercent.toInt()}%")
        }
        .joinToString(" · ")

@Composable
fun Artwork(source: String, size: Int = 44, modifier: Modifier = Modifier, youtubeId: String = "") {
    val candidates = remember(source, youtubeId) { thumbnailCandidates(source, youtubeId) }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Glyph("music-2", modifier = Modifier.size((size * .45).dp))
        candidates.getOrNull(attempt)?.let { url ->
            key(source, attempt) {
                AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    onError = { if (candidates.getOrNull(attempt) == url) attempt++ })
            }
        }
    }
}

@Composable
fun Action(label: String, icon: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (icon.isNotBlank()) {
            Glyph(icon, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, maxLines = 1)
    }
}

@Composable
fun IconAction(
    icon: String,
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Glyph(icon, label, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
fun Section(title: String) {
    Text(
        title,
        Modifier.padding(top = 20.dp, bottom = 8.dp),
        fontSize = 19.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun Hint(value: String) {
    if (value.isNotBlank())
        Text(
            value,
            Modifier.padding(vertical = 5.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

@Composable
fun Progress(stage: String, progress: Float = -1f) {
    Column(Modifier.fillMaxWidth()) {
        Hint(stage)
        if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        else
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
    }
}

@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: String = "",
    trailing: @Composable (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Default),
        keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
        leadingIcon =
            if (icon.isBlank()) null
            else {
                { Glyph(icon) }
            },
        trailingIcon = trailing,
    )
}

@Composable
fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
    }
}

@Composable
fun <T> PickMenu(
    value: T,
    choices: List<T>,
    label: (T) -> String = { it.toString() },
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Action(label(value), "chevron-down") { open = true }
        DropdownMenu(open, { open = false }) {
            for (choice in choices) DropdownMenuItem(
                text = { Text(label(choice)) },
                onClick = {
                    open = false
                    onPick(choice)
                },
            )
        }
    }
}

@Composable
fun Confirmation(
    title: String,
    text: String,
    confirm: String = "Confirm",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Action(confirm) {
                onConfirm()
                onDismiss()
            }
        },
        dismissButton = { Action("Cancel") { onDismiss() } },
    )
}

@Composable
fun SeekLine(position: Long, duration: Long, identity: String, onSeek: (Long) -> Unit) {
    var dragging by remember(identity) { mutableStateOf(false) }
    var preview by remember(identity) { mutableFloatStateOf(0f) }
    val latestPosition by rememberUpdatedState(position)
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        if (duration > 0) position.toFloat() / duration else 0f,
                        0f..1f,
                    )
                if (duration > 0)
                    setProgress {
                        onSeek((it * duration).toLong())
                        true
                    }
            }
            .pointerInput(duration, identity) {
                if (duration <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // ignore touches offered through Compose's expanded hit slop A title tap below this canvas must never seek
                    if (down.position.x !in 0f..size.width.toFloat() ||
                        down.position.y !in 0f..size.height.toFloat()) return@awaitEachGesture
                    dragging = true
                    preview = (down.position.x / size.width).coerceIn(0f, 1f)
                    down.consume()
                    val completed = drag(down.id) { change ->
                        preview = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    }
                    dragging = false
                    if (completed) onSeek((preview * duration).roundToLong())
                }
            }
    ) {
        val fraction =
            if (dragging) preview
            else if (duration > 0) (latestPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
        drawLine(rail, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        drawLine(
            accent,
            Offset(0f, size.height / 2),
            Offset(size.width * fraction, size.height / 2),
            2.dp.toPx(),
        )
        if (dragging) drawCircle(accent, 5.dp.toPx(), Offset(size.width * fraction, size.height / 2))
    }
}

@Stable
class DragReorderState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val canSwapTo: (Int) -> Boolean,
    private val onMove: (Int, Int) -> Unit,
    private val onDragEnd: () -> Unit = {},
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    private var draggedIndex by mutableIntStateOf(-1)

    fun start(key: Any, index: Int) {
        draggedKey = key
        draggedIndex = index
        dragOffsetY = 0f
    }

    fun dragBy(deltaY: Float) {
        if (draggedKey == null) return
        dragOffsetY += deltaY
        val layoutInfo = listState.layoutInfo
        val currentInfo =
            layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedKey } ?: return
        draggedIndex = currentInfo.index
        val currentCenter = currentInfo.offset + currentInfo.size / 2f + dragOffsetY
        val target =
            layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.index != draggedIndex &&
                    canSwapTo(item.index) &&
                    currentCenter in item.offset.toFloat()..(item.offset + item.size).toFloat()
            }
        if (target != null) {
            val from = draggedIndex
            val to = target.index
            onMove(from, to)
            draggedIndex = to
            dragOffsetY += (currentInfo.offset - target.offset)
        }
        val viewportTop = layoutInfo.viewportStartOffset.toFloat()
        val viewportBottom = layoutInfo.viewportEndOffset.toFloat()
        val itemTop = currentInfo.offset + dragOffsetY
        val itemBottom = itemTop + currentInfo.size
        val edge = 96f
        val scrollDelta =
            when {
                itemTop < viewportTop + edge -> -18f
                itemBottom > viewportBottom - edge -> 18f
                else -> 0f
            }
        if (scrollDelta != 0f) {
            scope.launch { listState.scrollBy(scrollDelta) }
        }
    }

    fun finish() {
        val wasDragging = draggedKey != null
        draggedKey = null
        draggedIndex = -1
        dragOffsetY = 0f
        if (wasDragging) onDragEnd()
    }
}

fun Modifier.draggableRow(state: DragReorderState, key: Any): Modifier {
    val active = state.draggedKey == key
    return this
        .zIndex(if (active) 1f else 0f)
        .graphicsLayer {
            if (active) {
                translationY = state.dragOffsetY
                shadowElevation = 10f
                scaleX = 1.01f
                scaleY = 1.01f
            }
        }
}

@Composable
fun DragReorderHandle(state: DragReorderState, key: Any, index: Int) {
    val latestIndex by rememberUpdatedState(index)
    Glyph(
        "grip-vertical",
        "Drag to reorder",
        Modifier.size(38.dp).padding(8.dp).pointerInput(state, key) {
            detectDragGestures(
                onDragStart = { state.start(key, latestIndex) },
                onDragEnd = { state.finish() },
                onDragCancel = { state.finish() },
            ) { change, amount ->
                change.consume()
                state.dragBy(amount.y)
            }
        },
    )
}

@Composable
fun DragGrip(onStep: (Int) -> Unit) {
    var delta by remember { mutableFloatStateOf(0f) }
    val latest by rememberUpdatedState(onStep)
    Glyph(
        "grip-vertical",
        "Drag to reorder",
        Modifier.size(38.dp).padding(8.dp).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { delta = 0f },
                onDragEnd = { delta = 0f },
                onDragCancel = { delta = 0f },
            ) { change, amount ->
                change.consume()
                delta += amount.y
                val step = 72.dp.toPx()
                if (delta > step) {
                    latest(1)
                    delta -= step
                } else if (delta < -step) {
                    latest(-1)
                    delta += step
                }
            }
        },
    )
}
