package dev.sillyplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged

private data class SongRow(val key: String, val track: Track, val member: Member? = null)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MusicScreen(
    g: Graph,
    pickers: Pickers,
    onExpand: () -> Unit,
    onAction: (Track, String) -> Unit,
) {
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val tracks by g.library.tracks.collectAsStateWithLifecycle()
    val collections by g.library.collections.collectAsStateWithLifecycle()
    val members by g.library.members.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Songs") }
    var collectionId by rememberSaveable { mutableStateOf("") }
    var collectionName by rememberSaveable { mutableStateOf("") }
    val needsLikes = collectionId == "liked" || (collectionId.isBlank() && tab == "Playlists")
    val likesSource = remember(needsLikes) { if(needsLikes) g.library.likes else flowOf(emptySet<String>()) }
    val likes by likesSource.collectAsStateWithLifecycle(emptySet())
    val needsRecent = collectionId == "recent" || (collectionId.isBlank() && tab == "Playlists")
    val recentSource = remember(needsRecent) { if(needsRecent) g.dao.observeRecentTrackIds().distinctUntilChanged() else flowOf(emptyList<String>()) }
    val recent by recentSource.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("Recently added") }
    var albumType by rememberSaveable { mutableStateOf("Generated") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<dev.sillyplayer.data.Collection?>(null) }
    var delete by remember { mutableStateOf<dev.sillyplayer.data.Collection?>(null) }
    var collectionMenu by remember { mutableStateOf<dev.sillyplayer.data.Collection?>(null) }
    var addTo by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var showSongPicker by rememberSaveable { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var batchEdit by remember { mutableStateOf(false) }
    var batchArtist by remember { mutableStateOf("") }
    var batchAlbum by remember { mutableStateOf("") }
    var batchAlbumArtist by remember { mutableStateOf("") }
    var batchCover by remember { mutableStateOf("") }
    var cleanPreview by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberSaveable(tab, collectionId, saver = LazyListState.Saver) { LazyListState() }
    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val collectionById = remember(collections) { collections.associateBy { it.id } }
    val membersByCollection = remember(members) { members.groupBy { it.collection } }
    fun closeSelection() {
        selected = emptyList()
        selecting = false
    }
    fun open(id: String, title: String) {
        collectionId = id
        collectionName = title
        query = ""
        closeSelection()
        editing = false
        showSongPicker = false
    }
    BackHandler(selecting || collectionId.isNotEmpty()) {
        if (selecting) closeSelection() else open("", "")
    }
    val orderedRows by produceState<List<SongRow>>(emptyList(), tracks, members, likes, recent, collectionId, sort) {
        value = withContext(Dispatchers.Default) {
            val values: List<SongRow> =
                when {
                    collectionId == "liked" ->
                        tracks.filter { it.id in likes }.map { SongRow(it.id, it) }
                    collectionId == "recent" ->
                        recent.mapNotNull { trackById[it]?.let { t -> SongRow(t.id, t) } }
                    collectionId.startsWith("artist:") -> {
                        val targetArtist = collectionId.removePrefix("artist:")
                        tracks
                            .filter { track ->
                                splitArtists(track.artist).any {
                                    it.lowercase(Locale.ROOT) == targetArtist
                                }
                            }
                            .map { SongRow(it.id, it) }
                    }
                    collectionId.startsWith("album:") ->
                        tracks
                            .filter {
                                (it.albumArtist
                                    .ifBlank { it.artist }
                                    .trim()
                                    .lowercase(Locale.ROOT) +
                                    "|" +
                                    it.album.trim().lowercase(Locale.ROOT)) ==
                                    collectionId.removePrefix("album:")
                            }
                            .map { SongRow(it.id, it) }
                    collectionId.isNotBlank() ->
                        (membersByCollection[collectionId] ?: emptyList())
                            .mapNotNull { m -> trackById[m.track]?.let { SongRow(m.id, it, m) } }
                    else -> tracks.map { SongRow(it.id, it) }
                }
            val ordered =
                if (collectionId.isNotBlank()) values
                else
                    when (sort) {
                        "Title" -> values.sortedBy { it.track.title.lowercase() }
                        "Artist" -> values.sortedBy { it.track.artist.lowercase() }
                        "Duration" -> values.sortedBy { it.track.duration }
                        else -> values
                    }
            ordered
        }
    }
    val rows by produceState(orderedRows, orderedRows, query) {
        if (query.isNotBlank()) delay(120)
        value = withContext(Dispatchers.Default) {
            if (query.isBlank()) orderedRows else orderedRows.filter {
                it.track.title.contains(query, true) || it.track.artist.contains(query, true) ||
                    it.track.album.contains(query, true)
            }
        }
    }
    val playbackContext =
        when {
            collectionId.isBlank() -> "Songs"
            collectionId == "liked" || collectionId == "recent" -> "Playlist: $collectionName"
            collectionId.startsWith("artist:") -> "Artist: $collectionName"
            collectionId.startsWith("album:") -> "Album: $collectionName"
            collections.firstOrNull { it.id == collectionId }?.kind == "custom" ->
                "Custom album: $collectionName"
            else -> "Playlist: $collectionName"
        }
    val showingSongs = tab == "Songs" || collectionId.isNotBlank()
    val selectedKeys = remember(selected) { selected.toHashSet() }
    val chosen = remember(orderedRows, selectedKeys) { if(selectedKeys.isEmpty()) emptyList() else orderedRows.filter { it.key in selectedKeys } }
    val actualIds = chosen.map { it.track.id }
    val all = rows.isNotEmpty() && selectedKeys.size >= rows.size && rows.all { it.key in selectedKeys }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyUp && it.key == Key.Escape && selecting) {
                closeSelection()
                true
            } else false
        }
    ) {
        if (prefs.musicDir.isBlank())
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Choose where songs are saved", Modifier.weight(1f), fontSize = 12.sp)
                Action("Choose folder", "folder") { pickers.folder("destination") }
            }
        if (prefs.musicDir.isBlank()) PermissionNotice(pickers, compact = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (item in listOf("Songs", "Playlists", "Artists", "Albums")) TextButton(
                onClick = {
                    tab = item
                    open("", "")
                }
            ) {
                Text(
                    item,
                    color =
                        if (tab == item) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (collectionId.isNotBlank())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction("arrow-left", "Back") { open("", "") }
                Text(
                    collectionName,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (orderedRows.isNotEmpty() && (collectionId == "liked" || collectionId == "recent" || collections.any { it.id == collectionId })) {
                    IconAction("download", "Export playlist (.m3u8)") {
                        pickers.exportPlaylist(collectionId, collectionName)
                    }
                }
                if (collections.any { it.id == collectionId }) {
                    Action(if (editing) "Done" else "Edit tracks") {
                        editing = !editing
                        closeSelection()
                    }
                    IconAction("ellipsis", "Edit playlist") {
                        collectionMenu = collections.first { it.id == collectionId }
                    }
                }
            }
        if (tab == "Albums" && collectionId.isBlank())
            Row {
                Action("Generated", enabled = albumType != "Generated") { albumType = "Generated" }
                Action("Custom", enabled = albumType != "Custom") { albumType = "Custom" }
            }
        Input(
            query,
            { query = it },
            if (showingSongs) "Search songs" else "Find $tab",
            Modifier.padding(horizontal = 12.dp),
            "search",
        )
        if (showingSongs) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 7.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (collections.any { it.id == collectionId && it.kind in listOf("playlist", "custom") })
                    Action("Add songs", "plus") { showSongPicker = true }
                if (!selecting)
                    Action("Select songs", "list-checks", rows.isNotEmpty()) { selecting = true }
                else {
                    Action(if (all) "Deselect all" else "Select all") {
                        selected = if (all) emptyList() else rows.map { it.key }
                    }
                    Action("Clear", "x") { closeSelection() }
                }
                if (collectionId.isBlank())
                    PickMenu(sort, listOf("Recently added", "Title", "Artist", "Duration")) {
                        sort = it
                    }
            }
            if (selecting)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 7.dp)) {
                    Action("Queue", "list-music", chosen.isNotEmpty()) {
                        g.engine.value?.addQueue(chosen.map { it.track }, false)
                    }
                    Action("Play next", "skip-forward", chosen.isNotEmpty()) {
                        g.engine.value?.addQueue(chosen.map { it.track }, true)
                    }
                    Action("Add to playlist", "plus", chosen.isNotEmpty()) { addTo = true }
                    Action("Edit tags", "pencil", chosen.isNotEmpty()) {
                        batchArtist = ""
                        batchAlbum = ""
                        batchAlbumArtist = ""
                        batchCover = ""
                        batchEdit = true
                    }
                    Action("Clean titles", "check", chosen.isNotEmpty()) {
                        val preview =
                            chosen
                                .distinctBy { it.track.id }
                                .mapNotNull { row ->
                                    val cleaned = cleanSongTitle(row.track.title)
                                    if (cleaned != row.track.title)
                                        Triple(row.track.id, row.track.title, cleaned)
                                    else null
                                }
                        if (preview.isEmpty()) {
                            g.message.value = "All selected titles are already clean"
                        } else {
                            cleanPreview = preview
                        }
                    }
                    if (collectionId.isNotBlank() && chosen.any { it.member != null })
                        Action("Remove", "x", chosen.isNotEmpty()) {
                            g.task {
                                chosen
                                    .mapNotNull { it.member }
                                    .forEach { g.library.removeMember(it.id) }
                            }
                            closeSelection()
                        }
                    Action("Trash", "trash-2", chosen.isNotEmpty()) { trash = true }
                }
            val canDragReorder = editing && query.isBlank() && collectionId.isNotBlank() && collections.any { it.id == collectionId }
            var liveOrder by remember(rows, canDragReorder) { mutableStateOf(rows) }
            val displayedRows = if (canDragReorder) liveOrder else rows
            val reorderState =
                remember(list, scope, collectionId) {
                    DragReorderState(
                        listState = list,
                        scope = scope,
                        canSwapTo = { idx -> idx in liveOrder.indices && liveOrder[idx].member != null },
                        onMove = { from, to ->
                            if (from in liveOrder.indices && to in liveOrder.indices && from != to) {
                                liveOrder = liveOrder.toMutableList().apply { add(to, removeAt(from)) }
                            }
                        },
                        onDragEnd = {
                            val updatedMembers = liveOrder.mapNotNull { it.member }
                            if (updatedMembers.isNotEmpty()) {
                                g.task { g.library.reorder(updatedMembers) }
                            }
                        },
                    )
                }
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(displayedRows, key = { _, row -> row.key }) { index, row ->
                    val dragging = canDragReorder && reorderState.draggedKey == row.key
                    TrackListRow(
                        row.track,
                        row.key in selectedKeys,
                        selecting,
                        canDragReorder && row.member != null,
                        modifier =
                            if (canDragReorder)
                                Modifier.draggableRow(reorderState, row.key)
                                    .background(
                                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                            else Modifier,
                        dragHandle =
                            if (canDragReorder && row.member != null) {
                                { DragReorderHandle(reorderState, row.key, index) }
                            } else null,
                        onSelect = {
                            selected =
                                if (row.key in selectedKeys) selected - row.key else selected + row.key
                        },
                        onLong = {
                            selecting = true
                            selected = (selected + row.key).distinct()
                        },
                        onPlay = {
                            g.engine.value?.playTrack(
                                row.track,
                                displayedRows.map { it.track },
                                playbackContext,
                                index,
                            )
                        },
                        onExpand = {
                            g.engine.value?.playTrack(
                                row.track,
                                displayedRows.map { it.track },
                                playbackContext,
                                index,
                            )
                            onExpand()
                        },
                        onAction = { onAction(row.track, it) },
                    )
                }
                if (rows.isEmpty())
                    item {
                        Hint(
                            if (tracks.isEmpty())
                                "Add local music or restore a desktop backup in Settings."
                            else "No matching songs"
                        )
                    }
            }
        } else {
            val groups by produceState<List<Triple<String, String, List<Track>>>>(
                emptyList(), tab, albumType, tracks, membersByCollection, collections, likes, recent) {
                value = withContext(Dispatchers.Default) {
                when (tab) {
                    "Artists" -> {
                        val buckets = LinkedHashMap<String, Pair<String, MutableList<Track>>>()
                        for (track in tracks) {
                            for (artistName in splitArtists(track.artist)) {
                                val key = artistName.lowercase(Locale.ROOT)
                                val entry = buckets.getOrPut(key) { artistName to mutableListOf() }
                                entry.second += track
                            }
                        }
                        buckets
                            .map { (key, entry) -> Triple("artist:$key", entry.first, entry.second.toList()) }
                            .sortedBy { it.second.lowercase(Locale.ROOT) }
                    }
                    "Albums" ->
                        if (albumType == "Custom")
                            collections
                                .filter { it.kind == "custom" }
                                .map { c ->
                                    Triple(
                                        c.id,
                                        c.name,
                                        (membersByCollection[c.id] ?: emptyList())
                                            .mapNotNull { trackById[it.track] },
                                    )
                                }
                        else
                            tracks
                                .filter { it.album.isNotBlank() }
                                .groupBy {
                                    it.albumArtist
                                        .ifBlank { it.artist }
                                        .trim()
                                        .lowercase(Locale.ROOT) +
                                        "|" +
                                        it.album.trim().lowercase(Locale.ROOT)
                                }
                                .map { (key, values) ->
                                    Triple("album:" + key, values.first().album, values)
                                }
                                .sortedBy { it.second.lowercase() }
                    else ->
                        listOf(
                            Triple("liked", "Liked Songs", tracks.filter { it.id in likes }),
                            Triple("recent", "Recently Played", recent.mapNotNull { trackById[it] }),
                        ) +
                            collections
                                .filter { it.kind == "playlist" }
                                .map { c ->
                                    Triple(
                                        c.id,
                                        c.name,
                                        (membersByCollection[c.id] ?: emptyList())
                                            .mapNotNull { trackById[it.track] },
                                    )
                                }
                }
                }
            }
            LazyColumn(Modifier.weight(1f), state = list) {
                if (tab == "Playlists" || tab == "Albums" && albumType == "Custom")
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    name = ""
                                    create = true
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Glyph("plus")
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (tab == "Playlists") "New Playlist" else "New Album",
                                fontSize = 14.sp,
                            )
                        }
                    }
                if (tab == "Playlists")
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { pickers.importPlaylist() }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Glyph("download")
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Import Playlist (.m3u / .m3u8)", fontSize = 14.sp)
                                Text(
                                    "Match songs from your library",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                items(
                    groups.filter { query.isBlank() || it.second.contains(query, true) },
                    key = { it.first },
                ) { (id, title, values) ->
                    val collection = collectionById[id]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { open(id, title) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(
                            collection?.art?.takeIf { it.isNotBlank() }
                                ?: values.firstOrNull { it.art.isNotBlank() }?.art
                                ?: ""
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (values.size == 1) "1 song" else "${values.size} songs",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (collection != null)
                            IconAction("ellipsis", "Collection actions") {
                                collectionMenu = collection
                            }
                    }
                }
            }
        }
    }
    if (create || rename != null)
        AlertDialog(
            onDismissRequest = {
                create = false
                rename = null
            },
            title = {
                Text(
                    if (rename != null) "Edit collection"
                    else if (tab == "Playlists") "New Playlist" else "New Album"
                )
            },
            text = { Input(name, { name = it }, "Name") },
            confirmButton = {
                Action("Save", enabled = name.isNotBlank()) {
                    val old = rename
                    g.task {
                        g.library.collection(
                            name,
                            old?.kind ?: if (tab == "Playlists") "playlist" else "custom",
                            old?.id ?: uuid(),
                            old?.art ?: "",
                        )
                    }
                    create = false
                    rename = null
                }
            },
            dismissButton = {
                Action("Cancel") {
                    create = false
                    rename = null
                }
            },
        )
    collectionMenu?.let { c ->
        AlertDialog(
            onDismissRequest = { collectionMenu = null },
            title = { Text(c.name) },
            text = {
                Column {
                    Action("Edit playlist", "pencil") {
                        name = c.name
                        rename = c
                        collectionMenu = null
                    }
                    Action("Change cover", "image") {
                        collectionMenu = null
                        pickers.image { uri ->
                            g.task { g.library.collection(c.name, c.kind, c.id, uri) }
                        }
                    }
                    Action("Export playlist (.m3u8)", "download") {
                        collectionMenu = null
                        pickers.exportPlaylist(c.id, c.name)
                    }
                    Action("Delete playlist", "trash-2") {
                        delete = c
                        collectionMenu = null
                    }
                }
            },
            confirmButton = { Action("Close") { collectionMenu = null } },
        )
    }
    delete?.let { c ->
        Confirmation("Delete ${c.name}?", "Audio files are kept.", "Delete", { delete = null }) {
            g.task { g.library.deleteCollection(c.id) }
            if (collectionId == c.id) open("", "")
        }
    }
    if (addTo)
        PlaylistChooser(g, actualIds, { addTo = false }) { collection, duplicates ->
            g.task {
                if (!g.library.add(collection, actualIds, duplicates)) duplicate = collection to actualIds.toList()
                else addTo = false
            }
        }
    if (showSongPicker && collections.any { it.id == collectionId && it.kind in listOf("playlist", "custom") })
        PlaylistSongPicker(tracks, { showSongPicker = false }) { ids ->
            val target = collectionId
            showSongPicker = false
            g.task {
                if (!g.library.add(target, ids)) duplicate = target to ids
            }
        }
    duplicate?.let { (id, ids) ->
        Confirmation(
            "Already in playlist",
            "Some selected songs are already in this playlist. Add them again?",
            "Add again",
            { duplicate = null },
        ) {
            g.task { g.library.add(id, ids, true) }
            addTo = false
        }
    }
    if (trash)
        Confirmation(
            "Move ${actualIds.distinct().size} songs to Trash?",
            "Local files stay recoverable until you empty Trash. Unavailable songs are removed as library entries; removals sync to your paired desktop.",
            "Move to Trash",
            { trash = false },
        ) {
            g.task { g.library.trash(actualIds.distinct()) }
            closeSelection()
        }
    if (batchEdit)
        AlertDialog(
            onDismissRequest = { batchEdit = false },
            title = { Text("Batch edit tags") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Hint("${actualIds.distinct().size} selected songs · leave a field blank to keep each song's current value")
                    Input(batchArtist, { batchArtist = it }, "Artist / creator")
                    Input(batchAlbum, { batchAlbum = it }, "Album")
                    Input(batchAlbumArtist, { batchAlbumArtist = it }, "Album artist")
                    Action(
                        if (batchCover.isNotBlank()) "Artwork selected" else "Set shared cover",
                        "image",
                    ) {
                        pickers.image { batchCover = it }
                    }
                }
            },
            confirmButton = {
                Action(
                    "Apply",
                    enabled =
                        batchArtist.isNotBlank() ||
                            batchAlbum.isNotBlank() ||
                            batchAlbumArtist.isNotBlank() ||
                            batchCover.isNotBlank(),
                ) {
                    val ids = actualIds.distinct()
                    val a = batchArtist.trim().takeIf { it.isNotEmpty() }
                    val al = batchAlbum.trim().takeIf { it.isNotEmpty() }
                    val aa = batchAlbumArtist.trim().takeIf { it.isNotEmpty() }
                    val cv = batchCover.takeIf { it.isNotBlank() }
                    batchEdit = false
                    closeSelection()
                    g.task { g.message.value = g.library.batchEditTags(ids, a, al, aa, cv) }
                }
            },
            dismissButton = { Action("Cancel") { batchEdit = false } },
        )
    cleanPreview?.let { changes ->
        AlertDialog(
            onDismissRequest = { cleanPreview = null },
            title = { Text("Clean ${changes.size} song titles?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Hint("Removes video/audio junk like (Official Audio), (Official Video), [Lyrics], etc.")
                    for ((_, before, after) in changes.take(20)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(before, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("→ $after", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                Action("Clean titles", "check") {
                    val ids = changes.map { it.first }
                    cleanPreview = null
                    closeSelection()
                    g.task { g.message.value = g.library.cleanTitles(ids) }
                }
            },
            dismissButton = { Action("Cancel") { cleanPreview = null } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListRow(
    track: Track,
    selected: Boolean = false,
    selecting: Boolean = false,
    reordering: Boolean = false,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
    onSelect: () -> Unit = {},
    onLong: () -> Unit = {},
    onPlay: () -> Unit,
    onExpand: () -> Unit,
    onAction: (String) -> Unit,
    onStep: (Int) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .1f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .combinedClickable(
                onClick = { if (selecting) onSelect() else onPlay() },
                onLongClick = onLong,
            )
            .padding(start = 12.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(selected, { onSelect() }, Modifier.size(32.dp))
        if (reordering) {
            if (dragHandle != null) dragHandle() else DragGrip(onStep)
        }
        Artwork(track.art, 44, Modifier.clickable { if (selecting) onSelect() else onExpand() })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title.ifBlank { "Unknown title" },
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { "Unknown artist" } +
                    if (!track.available) " · Unavailable" else "",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val modifier = modifierLabel(track)
            if (modifier.isNotBlank())
                Text(
                    modifier,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        Text(
            time((track.duration * 1000).toLong()),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconAction("ellipsis", "Song actions") { menu = true }
            DropdownMenu(menu, { menu = false }) {
                for ((label, action, icon) in
                    listOf(
                        Triple("Play next", "next", "skip-forward"),
                        Triple("Add to playlist", "add", "list-music"),
                        Triple("Playback speed", "speed", "play"),
                        Triple("Song volume", "volume", "volume-2"),
                        Triple("Equalizer", "eq", "settings"),
                        Triple("Like / unlike", "like", "heart"),
                        Triple("Edit metadata", "metadata", "pencil"),
                        Triple("Information", "info", "info"),
                        Triple("Link", "link", "external-link"),
                        Triple("Move to Trash", "trash", "trash-2"),
                    )) DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Glyph(icon) },
                    onClick = {
                        menu = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

@Composable
fun PlaylistChooser(
    g: Graph,
    ids: List<String>,
    onDismiss: () -> Unit,
    onPick: (String, Boolean) -> Unit,
) {
    val collections by g.library.collections.collectAsStateWithLifecycle()
    val playlistMembers by g.library.members.collectAsStateWithLifecycle()
    val libraryTracks by g.library.tracks.collectAsStateWithLifecycle()
    val covers = remember(libraryTracks) { libraryTracks.associate { it.id to it.art } }
    val membersByCollection = remember(playlistMembers) { playlistMembers.groupBy { it.collection } }
    var search by rememberSaveable { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${ids.size} songs to playlist") },
        text = {
            Column {
                Input(search, { search = it }, "Find a playlist", icon = "search")
                Action("New Playlist", "plus") { create = true }
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    for (c in collections.filter { it.kind in listOf("playlist", "custom") && it.name.contains(search, true) }) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(c.id, false) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val entries = membersByCollection[c.id].orEmpty()
                            Artwork(c.art.ifBlank { entries.firstNotNullOfOrNull { covers[it.track]?.takeIf(String::isNotBlank) }.orEmpty() }, 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Hint("${entries.size} songs")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Action("Close") { onDismiss() } },
    )
    if (create)
        AlertDialog(
            onDismissRequest = { create = false },
            title = { Text("New Playlist") },
            text = { Input(name, { name = it }, "Name") },
            confirmButton = {
                Action("Create", enabled = name.isNotBlank()) {
                    g.task {
                        val id = g.library.collection(name)
                        onPick(id, true)
                    }
                    create = false
                }
            },
            dismissButton = { Action("Cancel") { create = false } },
        )
}


@Composable
private fun PlaylistSongPicker(tracks: List<Track>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selectedIds = remember(selected) { selected.toHashSet() }
    val filtered = remember(tracks, query) {
        tracks.filter { track ->
            query.isBlank() || track.title.contains(query, true) ||
                track.artist.contains(query, true) || track.album.contains(query, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add songs") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Input(query, { query = it }, "Search library", icon = "search")
                Hint("${selected.size} songs selected")
                if (filtered.isEmpty()) Hint(if (tracks.isEmpty()) "Your library has no songs yet" else "No matching songs")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(filtered, key = { it.id }) { track ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                                selected = if (track.id in selectedIds) selected - track.id else selected + track.id
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(track.id in selectedIds, onCheckedChange = null)
                            Artwork(track.art, 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist + if (!track.available) " · Unavailable" else "", fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Action("Add selected (${selected.size})", enabled = selected.isNotEmpty()) {
                onAdd(tracks.asSequence().filter { it.id in selectedIds }.map { it.id }.toList())
            }
        },
        dismissButton = { Action("Cancel", onClick = onDismiss) },
    )
}
