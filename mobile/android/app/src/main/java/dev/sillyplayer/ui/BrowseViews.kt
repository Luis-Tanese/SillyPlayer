package dev.sillyplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import dev.sillyplayer.media.PlaybackState
import dev.sillyplayer.online.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(g: Graph, pickers: Pickers) {
    var provider by rememberSaveable { mutableStateOf("youtube") }
    val states by g.online.searches.collectAsStateWithLifecycle()
    val opened by g.online.opened.collectAsStateWithLifecycle()
    val resolving by g.online.resolving.collectAsStateWithLifecycle()
    val history by g.online.history.collectAsStateWithLifecycle()
    val collection by g.online.collection.collectAsStateWithLifecycle()
    var collectionInput by remember { mutableStateOf(false) }
    var collectionUrl by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf<Source?>(null) }
    val holder = rememberSaveableStateHolder()
    val engine by g.engine.collectAsStateWithLifecycle()
    val playback by
        (engine?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlaybackState()) })
            .collectAsStateWithLifecycle()
    LaunchedEffect(opened?.provider) { opened?.let { provider = it.provider } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((key, label) in
                listOf(
                    "youtube" to "YouTube",
                    "soundcloud" to "SoundCloud",
                    "direct" to "Direct link",
                )) {
                val active = provider == key
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            provider = key
                            g.online.closePlayer()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color =
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.width(24.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                    )
                }
            }
        }
        holder.SaveableStateProvider(provider) {
            val state = states[provider] ?: SearchState()
            var query by rememberSaveable { mutableStateOf(state.query) }
            val list = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val keyboard = LocalSoftwareKeyboardController.current
            val submitSearch = {
                if (query.isNotBlank()) {
                    g.online.search(provider, query.trim())
                    keyboard?.hide()
                }
            }
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SearchBarField(
                        value = query,
                        onChange = { query = it },
                        placeholder =
                            if (provider == "direct") "Paste an audio link"
                            else "Search or paste a link",
                        modifier = Modifier.weight(1f),
                        trailing = {
                            IconButton(
                                onClick = submitSearch,
                                enabled = query.isNotBlank(),
                                modifier = Modifier.size(34.dp),
                            ) {
                                Glyph(
                                    "arrow-right",
                                    "Search",
                                    Modifier.size(18.dp),
                                    tint = if (query.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onSubmit = submitSearch,
                    )
                    if (provider == "youtube") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            IconButton(onClick = { collectionInput = true }, modifier = Modifier.size(44.dp)) {
                                Glyph("list-music", "Playlist or mix", Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (opened != null && opened?.provider == provider) {
                    val source = opened!!
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            Action("Back", "arrow-left") { g.online.closePlayer() }
                            Spacer(Modifier.weight(1f))
                            Action("Download", "download") { downloading = source }
                        }
                        if (
                            playback.owner == "online" &&
                                playback.online?.sourceProvider == "youtube-video" &&
                                playback.online?.sourceUrl == source.url &&
                                engine != null
                        )
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { context ->
                                    androidx.media3.ui.PlayerView(context).apply {
                                        useController = false
                                        player = engine?.player
                                    }
                                },
                                update = { it.player = engine?.player },
                                onRelease = { it.player = null },
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            )
                        else
                            Artwork(
                                source.thumbnail,
                                240,
                                Modifier.align(Alignment.CenterHorizontally)
                                    .fillMaxWidth(.85f)
                                    .aspectRatio(1f),
                                youtubeId = source.id.takeIf { source.provider == "youtube" }.orEmpty(),
                            )
                        if (source.provider == "youtube")
                            Row {
                                Action("Audio") { g.online.play(source) }
                                Action("Video") { g.online.play(source, true) }
                            }
                        Spacer(Modifier.height(14.dp))
                        Text(source.title, fontSize = 19.sp)
                        Hint(source.uploader)
                        if (resolving) Progress("Loading audio")
                        else {
                            val matching = playback.online?.sourceUrl == source.url
                            val duration =
                                if (matching && playback.owner == "online") playback.duration
                                else (source.duration * 1000).toLong()
                            PlaybackPosition(g, online = true) { currentPosition ->
                                val position = if (matching) currentPosition else 0
                                SeekLine(position, duration, source.url) { engine?.seek(it, false) }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconAction(
                                        if (matching && playback.owner == "online" && playback.playing)
                                            "pause"
                                        else "play",
                                        "Play / pause",
                                    ) {
                                        if (matching) engine?.toggleOnline() else g.online.retryPlayback()
                                    }
                                    Text(time(position), fontSize = 12.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        if (duration > 0) time(duration) else "Duration unavailable",
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            if (matching && playback.owner == "online" && playback.buffering)
                                Progress("Buffering")
                            if (matching && playback.owner == "online" && playback.error.isNotBlank()) {
                                Hint(playback.error)
                                Action("Retry", "rotate-ccw") {
                                    g.online.retryPlayback()
                                }
                            }
                            MasterVolume(g, true)
                        }
                    }
                } else if (state.query.isBlank() && !state.loading) {
                    LazyColumn(state = list, contentPadding = PaddingValues(12.dp)) {
                        item {
                            Hint(
                                if (provider == "direct")
                                    "Use a link to an audio file. Local music remains available offline."
                                else "Search for audio or paste a link."
                            )
                        }
                        items(history.filter { it.provider == provider }, key = { it.query }) {
                            entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    query = entry.query
                                    g.online.search(provider, entry.query)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    entry.query,
                                    Modifier.weight(1f).padding(vertical = 14.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconAction("x", "Remove search") {
                                    g.task { g.dao.deleteSearch(provider, entry.query) }
                                }
                            }
                        }
                        if (history.any { it.provider == provider })
                            item {
                                Action("Clear search history", "trash-2") {
                                    g.task { g.dao.clearSearches(provider) }
                                }
                            }
                    }
                } else
                    LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (state.loading) item { Progress("Searching") }
                        if (state.message.isNotBlank()) item { Hint(state.message) }
                        itemsIndexed(state.rows, key = { i, row -> "$i:${row.url}" }) { _, source ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = source.playable) { g.online.play(source) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(source.thumbnail, 72, youtubeId = source.id.takeIf { source.provider == "youtube" }.orEmpty())
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        source.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        source.uploader,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Hint(
                                        if (source.playable && source.duration > 0)
                                            time((source.duration * 1000).toLong())
                                        else source.reason
                                    )
                                }
                                IconAction("download", "Download", source.playable) {
                                    downloading = source
                                }
                            }
                        }
                        if (state.more)
                            item {
                                Action("Load more", enabled = !state.loading) {
                                    g.online.search(provider, state.query, true)
                                }
                            }
                    }
            }
        }
    }
    if (collectionInput)
        AlertDialog(
            onDismissRequest = { collectionInput = false },
            title = { Text("YouTube playlist / mix") },
            text = { Input(collectionUrl, { collectionUrl = it }, "Playlist or mix URL") },
            confirmButton = {
                Action("Load", enabled = collectionUrl.isNotBlank()) {
                    g.online.loadCollection(collectionUrl)
                    collectionInput = false
                }
            },
            dismissButton = { Action("Cancel") { collectionInput = false } },
        )
    if (collection != null) CollectionDownloadDialog(g, pickers)
    downloading?.let { source -> DownloadDialog(g, pickers, source) { downloading = null } }
}

@Composable
fun DownloadDialog(g: Graph, pickers: Pickers, source: Source, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf("Original") }
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val jobs by g.online.jobs.collectAsStateWithLifecycle()
    var duplicate by remember { mutableStateOf<DownloadJob?>(null) }
    var info by remember(source.url) { mutableStateOf<Stream?>(null) }
    LaunchedEffect(source.url) {
        try {
            info = g.online.resolve(source)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // format information is optional here, the worker reports download errors
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column {
                Text(source.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                PickMenu(
                    kind,
                    if (source.provider == "direct") listOf("Original")
                    else listOf("Original", "MP3"),
                ) {
                    kind = it
                }
                Hint(
                    info?.let {
                        "Best source audio" +
                            (if (it.bitrate > 0) " · ${it.bitrate} kbps" else "") +
                            " · " +
                            it.suffix.uppercase()
                    } ?: "Best available source audio"
                )
                Hint(
                    if (kind == "MP3") "MP3 encoding does not improve source quality."
                    else "Keeps the original source audio format."
                )
                Hint(prefs.musicDir.ifBlank { "No download folder selected" })
                Action("Choose folder", "folder") { pickers.folder("destination") }
            }
        },
        confirmButton = {
            Action("Download", enabled = prefs.musicDir.isNotBlank()) {
                val existing =
                    jobs.firstOrNull {
                        it.url == source.url && it.kind == kind && it.state == "Completed"
                    }
                if (existing != null) duplicate = existing
                else {
                    g.task { g.online.enqueue(source, kind) }
                    onDismiss()
                }
            }
        },
        dismissButton = { Action("Cancel") { onDismiss() } },
    )
    duplicate?.let { job ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("Already downloaded") },
            text = { Text("Reuse the existing file, skip, or download another copy.") },
            confirmButton = {
                Action("Download again") {
                    g.task { g.online.enqueue(source, kind, true) }
                    duplicate = null
                    onDismiss()
                }
            },
            dismissButton = {
                Row {
                    Action("Open") {
                        openFile(g, job.output)
                        duplicate = null
                        onDismiss()
                    }
                    Action("Skip") {
                        duplicate = null
                        onDismiss()
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionDownloadDialog(g: Graph, pickers: Pickers) {
    val collection by g.online.collection.collectAsStateWithLifecycle()
    val c = collection ?: return
    var kind by remember { mutableStateOf("Original") }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var again by remember { mutableStateOf(false) }
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    LaunchedEffect(c.title) { if (name.isBlank()) name = c.title }
    val all = c.rows.indices.filter { c.rows[it].playable }.toSet()
    ModalBottomSheet(onDismissRequest = { g.online.dismissCollection() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 14.dp)) {
            Text(
                c.title.ifBlank { "YouTube playlist / mix" },
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("${c.selected.size} selected · ${c.rows.size} loaded")
                Spacer(Modifier.weight(1f))
                Action(
                    if (all.isNotEmpty() && c.selected == all) "Deselect all" else "Select all"
                ) {
                    g.online.selectAll()
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(c.rows, key = { i, s -> "$i:${s.id}" }) { i, source ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = source.playable) { g.online.select(i) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(i in c.selected, { g.online.select(i) }, enabled = source.playable)
                        Artwork(source.thumbnail, 40, youtubeId = source.id)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${i+1}. ${source.title}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                            )
                            Hint(source.reason.ifBlank { source.uploader })
                        }
                    }
                }
                item {
                    if (c.loading) Progress("Loading playlist")
                    if (c.more)
                        Action("Load more", enabled = !c.loading) {
                            g.online.loadCollection(c.url, true)
                        }
                    if (c.loading) Action("Cancel loading") { g.online.cancelCollectionLoading() }
                    Hint(c.message)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PickMenu(kind, listOf("Original", "MP3")) { kind = it }
                Hint("Best available source")
                Spacer(Modifier.weight(1f))
                Action("Folder", "folder") { pickers.folder("destination") }
            }
            if (kind != "Video") Choice("Create a local playlist", create) { create = it }
            if (create) Input(name, { name = it }, "Playlist name")
            Choice("Download completed songs again", again) { again = it }
            Action(
                if (create) "Download and create playlist"
                else "Download ${c.selected.size} selected",
                "download",
                !c.loading &&
                    c.selected.isNotEmpty() &&
                    prefs.musicDir.isNotBlank() &&
                    (!create || name.isNotBlank()),
            ) {
                g.task {
                    g.online.enqueueCollection(
                        kind,
                        if (create && kind != "Video") name else null,
                        again,
                    )
                }
            }
            if (prefs.musicDir.isBlank()) Hint("Choose a download folder first")
            Spacer(Modifier.height(12.dp))
        }
    }
}

fun openFile(g: Graph, uri: String) {
    runCatching {
            g.context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(
                        Uri.parse(uri),
                        g.context.contentResolver.getType(Uri.parse(uri)) ?: "audio/*",
                    )
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
            )
        }
        .onFailure { g.message.value = "No application could open this audio file" }
}

@Composable
fun DownloadsScreen(g: Graph) {
    val jobs by g.online.jobs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", Modifier.weight(1f), fontSize = 20.sp)
            Action("Clear failed", "trash-2") { g.task { g.online.clearHistory(true) } }
            Action("Clear history") { g.task { g.online.clearHistory(false) } }
        }
        val order =
            listOf(
                "Active",
                "Resolving",
                "Queued",
                "Post-processing",
                "Paused",
                "Interrupted",
                "Failed",
                "Completed",
                "Cancelled",
            )
        val sorted =
            jobs.sortedWith(
                compareBy<DownloadJob> { order.indexOf(it.state) }.thenByDescending { it.created }
            )
        LazyColumn {
            itemsIndexed(sorted, key = { _, j -> j.id }) { i, job ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    if (i == 0 || sorted[i - 1].state != job.state)
                        Text(
                            job.state,
                            Modifier.padding(bottom = 7.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    Text(
                        job.title,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Hint(job.kind + " · " + job.destination)
                    if (job.state in listOf("Active", "Resolving", "Post-processing"))
                        Progress(job.stage, if (job.progress >= 0) job.progress / 100 else -1f)
                    if (job.error.isNotBlank()) Hint(job.error)
                    Row {
                        when (job.state) {
                            "Queued",
                            "Resolving",
                            "Active",
                            "Post-processing" -> {
                                Action("Pause", "pause") { g.task { g.online.stop(job.id, true) } }
                                Action("Cancel", "x") { g.task { g.online.stop(job.id, false) } }
                            }
                            "Paused",
                            "Interrupted",
                            "Failed" ->
                                Action(if (job.state == "Paused") "Resume" else "Retry", "play") {
                                    g.task { g.online.retry(job.id) }
                                }
                            "Completed" ->
                                Action("Open", "external-link") { openFile(g, job.output) }
                        }
                        if (
                            job.state !in listOf("Queued", "Resolving", "Active", "Post-processing")
                        )
                            Action("Remove", "trash-2") { g.task { g.online.remove(job.id) } }
                    }
                }
            }
            if (jobs.isEmpty()) item { Hint("Downloads and completed history appear here.") }
        }
    }
}
