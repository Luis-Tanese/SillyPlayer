package dev.sillyplayer.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.sillyplayer.data.Track
import kotlin.math.ceil
import kotlin.math.roundToInt
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

internal fun formatEta(remainingMs: Long): String {
    if (remainingMs < 0L) return ""
    val totalSec = ceil(remainingMs / 1000.0).toLong()
    if (totalSec <= 1L) return "< 1s left"
    if (totalSec < 60L) return "~${totalSec}s left"
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    if (mins < 60L) return if (secs > 0L) "~${mins}m ${secs}s left" else "~${mins}m left"
    val hours = mins / 60L
    val remMins = mins % 60L
    return if (remMins > 0L) "~${hours}h ${remMins}m left" else "~${hours}h left"
}

@Composable
fun Progress(stage: String, progress: Float = -1f) {
    val determinate = progress >= 0f
    val clamped = if (determinate) progress.coerceIn(0f, 1f) else -1f
    val percent = if (determinate) (clamped * 100f).roundToInt().coerceIn(0, 100) else 0
    var startMs by remember { mutableLongStateOf(0L) }
    var startFraction by remember { mutableFloatStateOf(0f) }
    var lastFraction by remember { mutableFloatStateOf(-1f) }
    var smoothedEtaMs by remember { mutableDoubleStateOf(-1.0) }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(determinate, clamped) {
        if (!determinate) {
            startMs = 0L
            startFraction = 0f
            lastFraction = -1f
            smoothedEtaMs = -1.0
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            if (startMs == 0L || clamped < lastFraction - 0.03f) {
                startMs = now
                startFraction = clamped
                smoothedEtaMs = -1.0
            }
            lastFraction = clamped
            if (clamped < 1f) {
                val elapsed = now - startMs
                val delta = clamped - startFraction
                if (elapsed >= 500L && delta >= 0.005f) {
                    val raw = (elapsed * (1.0 - clamped) / delta).coerceIn(0.0, 86_400_000.0)
                    smoothedEtaMs = if (smoothedEtaMs < 0.0) raw else smoothedEtaMs * 0.7 + raw * 0.3
                }
            } else {
                smoothedEtaMs = 0.0
            }
        }
    }

    LaunchedEffect(determinate, clamped >= 1f) {
        if (determinate && clamped < 1f) {
            while (isActive) {
                delay(1000)
                tick = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    val etaLabel = remember(determinate, clamped, startMs, startFraction, smoothedEtaMs, tick) {
        if (!determinate || clamped >= 1f) ""
        else if (smoothedEtaMs >= 0.0) "Estimated time left: ${formatEta(smoothedEtaMs.toLong())}"
        else "Estimating time left…"
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stage,
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (determinate) {
                Text(
                    "$percent%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!determinate) LinearProgressIndicator(Modifier.fillMaxWidth())
        else
            LinearProgressIndicator(
                progress = { clamped },
                modifier = Modifier.fillMaxWidth(),
            )
        if (etaLabel.isNotEmpty()) {
            Text(
                etaLabel,
                Modifier.padding(top = 3.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val shape = RoundedCornerShape(14.dp)
    OutlinedTextField(
        value,
        onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = shape,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
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
fun SearchBarField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val borderColor =
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, borderColor, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(
            "search",
            modifier = Modifier.size(18.dp),
            tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
                keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(34.dp)) {
                Glyph("x", "Clear search", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun ToolPillButton(
    label: String,
    icon: String = "",
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val bg =
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val border =
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outlineVariant
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Surface(
        shape = shape,
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.height(34.dp),
    ) {
        Row(
            Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon.isNotBlank()) {
                Glyph(icon, modifier = Modifier.size(15.dp), tint = contentColor)
            }
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SortControl(
    value: String,
    ascending: Boolean,
    options: List<String>,
    onPick: (String) -> Unit,
    onToggleDirection: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(34.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .clickable { open = true }
                        .padding(start = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        value,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Glyph("chevron-down", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(open, { open = false }) {
                    for (choice in options) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    choice,
                                    color = if (choice == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (choice == value) FontWeight.Medium else FontWeight.Normal,
                                )
                            },
                            trailingIcon = if (choice == value) { { Glyph("check", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) } } else null,
                            onClick = {
                                open = false
                                onPick(choice)
                            },
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable { onToggleDirection() }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(
                    if (ascending) "arrow-up" else "arrow-down",
                    if (ascending) "Ascending order" else "Descending order",
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
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
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(min = 36.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label(value),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Glyph("chevron-down", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }) {
            for (choice in choices) DropdownMenuItem(
                text = {
                    Text(
                        label(choice),
                        color = if (choice == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (choice == value) FontWeight.Medium else FontWeight.Normal,
                    )
                },
                trailingIcon = if (choice == value) { { Glyph("check", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary) } } else null,
                onClick = {
                    open = false
                    onPick(choice)
                },
            )
        }
    }
}

@Composable
fun ModalPrimaryButton(
    label: String,
    icon: String = "",
    enabled: Boolean = true,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors =
        if (danger)
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        else
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp),
        modifier = modifier.heightIn(min = 38.dp),
    ) {
        if (icon.isNotBlank()) {
            Glyph(icon, modifier = Modifier.size(15.dp), tint = LocalContentColor.current)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ModalTonalButton(
    label: String,
    icon: String = "",
    enabled: Boolean = true,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors =
        if (danger)
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        else
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
        modifier = modifier.heightIn(min = 38.dp),
    ) {
        if (icon.isNotBlank()) {
            Glyph(icon, modifier = Modifier.size(15.dp), tint = LocalContentColor.current)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ModalSectionCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg =
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bg,
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun ModalOptionRow(
    title: String,
    subtitle: String = "",
    icon: String = "",
    selected: Boolean = false,
    danger: Boolean = false,
    trailingIcon: String = "",
    onClick: () -> Unit,
) {
    val tint =
        when {
            danger -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val bg =
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
            danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.07f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon.isNotBlank()) {
                Glyph(icon, modifier = Modifier.size(18.dp), tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val rightIcon = trailingIcon.ifBlank { if (selected) "check" else "" }
            if (rightIcon.isNotBlank()) {
                Glyph(rightIcon, modifier = Modifier.size(16.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppModalDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String = "",
    icon: String = "",
    danger: Boolean = false,
    dismissible: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // kept simpler and flatter without chunky icon boxes or dividers, using FlowRow so 3 buttons never clip
    Dialog(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .widthIn(max = 440.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 8.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (icon.isNotBlank() && danger) {
                            Glyph(icon, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            title,
                            Modifier.weight(1f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        },
        content = content,
    )
}

@Composable
fun Confirmation(
    title: String,
    text: String,
    confirm: String = "Confirm",
    onDismiss: () -> Unit,
    danger: Boolean = false,
    icon: String = "",
    onConfirm: () -> Unit,
) {
    val destructive = danger || confirm.contains("delete", true) || confirm.contains("trash", true) || confirm.contains("unpair", true) || confirm.contains("remove", true)
    val badgeIcon = icon.ifBlank { if (destructive) "trash-2" else "" }
    AppModalDialog(
        onDismissRequest = onDismiss,
        title = title,
        icon = badgeIcon,
        danger = destructive,
        actions = {
            ModalTonalButton("Cancel", onClick = onDismiss)
            ModalPrimaryButton(confirm, danger = destructive) {
                onConfirm()
                onDismiss()
            }
        },
    ) {
        if (text.isNotBlank()) {
            Text(
                text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SeekLine(
    position: Long,
    duration: Long,
    identity: String,
    flushTop: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember(identity) { mutableStateOf(false) }
    var preview by remember(identity) { mutableFloatStateOf(0f) }
    val latestPosition by rememberUpdatedState(position)
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxWidth()
            .height(if (flushTop) 10.dp else 28.dp)
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
        val lineY = if (flushTop) 1.dp.toPx() else size.height / 2
        drawLine(rail, Offset(0f, lineY), Offset(size.width, lineY), 2.dp.toPx())
        drawLine(
            accent,
            Offset(0f, lineY),
            Offset(size.width * fraction, lineY),
            2.dp.toPx(),
        )
        if (dragging) {
            val thumbY = if (flushTop) 5.dp.toPx() else lineY
            drawCircle(accent, 5.dp.toPx(), Offset(size.width * fraction, thumbY))
        }
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
