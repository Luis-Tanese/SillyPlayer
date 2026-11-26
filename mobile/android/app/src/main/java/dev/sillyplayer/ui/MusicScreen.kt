package dev.sillyplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val roots by g.library.roots.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Songs") }
    var collectionId by rememberSaveable { mutableStateOf("") }
    var collectionName by rememberSaveable { mutableStateOf("") }
    var folderPath by rememberSaveable { mutableStateOf("") }
    val pinnedPlaylists = remember(prefs.pinnedPlaylists) { prefs.pinnedPlaylists.toSet() }
    val pinnedArtists = remember(prefs.pinnedArtists) { prefs.pinnedArtists.toSet() }
    fun togglePinPlaylist(id: String) {
        g.task {
            g.prefs.update { p ->
                p.copy(pinnedPlaylists = if (id in p.pinnedPlaylists) p.pinnedPlaylists - id else p.pinnedPlaylists + id)
            }
        }
    }
    fun togglePinArtist(id: String) {
        g.task {
            g.prefs.update { p ->
                p.copy(pinnedArtists = if (id in p.pinnedArtists) p.pinnedArtists - id else p.pinnedArtists + id)
            }
        }
    }
    val needsLikes = collectionId == "liked" || (collectionId.isBlank() && tab == "Playlists")
    val likesSource = remember(needsLikes) { if(needsLikes) g.library.likes else flowOf(emptySet<String>()) }
    val likes by likesSource.collectAsStateWithLifecycle(emptySet())
    val needsRecent = collectionId == "recent" || (collectionId.isBlank() && tab == "Playlists")
    val recentSource = remember(needsRecent) { if(needsRecent) g.dao.observeRecentTrackIds().distinctUntilChanged() else flowOf(emptyList<String>()) }
    val recent by recentSource.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("Recently added") }
    var sortAsc by rememberSaveable { mutableStateOf(false) }
    var playlistSort by rememberSaveable { mutableStateOf("Playlist order") }
    var playlistSortAsc by rememberSaveable { mutableStateOf(true) }
    var artistSort by rememberSaveable { mutableStateOf("Name") }
    var artistSortAsc by rememberSaveable { mutableStateOf(true) }
    var albumType by rememberSaveable { mutableStateOf("Generated") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var moreSelectionMenu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<dev.sillyplayer.data.Collection?>(null) }
    var delete by remember { mutableStateOf<dev.sillyplayer.data.Collection?>(null) }
    var collectionMenu by remember { mutableStateOf<dev.sillyplayer.data.Collection?>(null) }
    var removeDuplicatesTarget by remember { mutableStateOf<Pair<dev.sillyplayer.data.Collection, Int>?>(null) }
    var artistMenu by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addTo by remember { mutableStateOf(false) }
    var showSongPicker by rememberSaveable { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var batchEdit by remember { mutableStateOf(false) }
    var batchArtist by remember { mutableStateOf("") }
    var batchAlbum by remember { mutableStateOf("") }
    var batchAlbumArtist by remember { mutableStateOf("") }
    var batchCover by remember { mutableStateOf("") }
    var cleanPreview by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    val scope = rememberCoroutineScope()
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
        playlistSort = "Playlist order"
        playlistSortAsc = true
        closeSelection()
        editing = false
        showSongPicker = false
    }
    BackHandler(selecting || collectionId.isNotEmpty() || (tab == "Folders" && folderPath.isNotEmpty())) {
        when {
            selecting -> closeSelection()
            collectionId.isNotEmpty() -> open("", "")
            tab == "Folders" && folderPath.isNotEmpty() -> {
                folderPath = folderPath.substringBeforeLast('/', "")
                query = ""
            }
        }
    }
    val orderedState by produceState<Pair<String, List<SongRow>>>(
        "" to emptyList(), tracks, members, likes, recent, collectionId, sort, sortAsc, playlistSort, playlistSortAsc,
    ) {
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
                    collectionId.startsWith("album:") -> {
                        // dont split on | since generated album ids are just album:<name> and real album titles can contain |
                        val targetAlbum = collectionId.removePrefix("album:").trim().lowercase(Locale.ROOT)
                        tracks
                            .filter { it.album.trim().lowercase(Locale.ROOT) == targetAlbum }
                            .map { SongRow(it.id, it) }
                    }
                    collectionId.isNotBlank() ->
                        (membersByCollection[collectionId] ?: emptyList())
                            .mapNotNull { m -> trackById[m.track]?.let { SongRow(m.id, it, m) } }
                    else -> tracks.map { SongRow(it.id, it) }
                }
            val ordered =
                if (collectionId.isNotBlank()) {
                    when (playlistSort) {
                        "Recently added" ->
                            if (playlistSortAsc) values.sortedWith(compareBy<SongRow> { it.track.added }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                            else values.sortedWith(compareByDescending<SongRow> { it.track.added }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                        "Title" ->
                            if (playlistSortAsc) values.sortedBy { it.track.title.lowercase(Locale.ROOT) }
                            else values.sortedByDescending { it.track.title.lowercase(Locale.ROOT) }
                        "Artist" ->
                            if (playlistSortAsc) values.sortedWith(compareBy<SongRow> { it.track.artist.lowercase(Locale.ROOT) }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                            else values.sortedWith(compareByDescending<SongRow> { it.track.artist.lowercase(Locale.ROOT) }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                        "Duration" ->
                            if (playlistSortAsc) values.sortedWith(compareBy<SongRow> { it.track.duration }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                            else values.sortedWith(compareByDescending<SongRow> { it.track.duration }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                        else -> if (playlistSortAsc) values else values.reversed()
                    }
                } else
                    when (sort) {
                        "Title" ->
                            if (sortAsc) values.sortedBy { it.track.title.lowercase(Locale.ROOT) }
                            else values.sortedByDescending { it.track.title.lowercase(Locale.ROOT) }
                        "Artist" ->
                            if (sortAsc) values.sortedWith(compareBy<SongRow> { it.track.artist.lowercase(Locale.ROOT) }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                            else values.sortedWith(compareByDescending<SongRow> { it.track.artist.lowercase(Locale.ROOT) }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                        "Duration" ->
                            if (sortAsc) values.sortedWith(compareBy<SongRow> { it.track.duration }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                            else values.sortedWith(compareByDescending<SongRow> { it.track.duration }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                        else ->
                            if (sortAsc) values.sortedWith(compareBy<SongRow> { it.track.added }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                            else values.sortedWith(compareByDescending<SongRow> { it.track.added }.thenBy { it.track.title.lowercase(Locale.ROOT) })
                    }
            "$collectionId|$sort|$sortAsc|$playlistSort|$playlistSortAsc" to ordered
        }
    }
    val orderedRows = orderedState.second
    val rowsState by produceState(orderedState, orderedState, query) {
        if (query.isNotBlank()) delay(120)
        value = withContext(Dispatchers.Default) {
            val filtered =
                if (query.isBlank()) orderedState.second
                else orderedState.second.filter {
                    it.track.title.contains(query, true) || it.track.artist.contains(query, true) ||
                        it.track.album.contains(query, true)
                }
            orderedState.first to filtered
        }
    }
    val rows = rowsState.second
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (item in listOf("Songs", "Playlists", "Artists", "Albums", "Folders")) {
                val active = tab == item
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            tab = item
                            if (item == "Folders") folderPath = ""
                            open("", "")
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        item,
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
        if (collectionId.isNotBlank())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction("arrow-left", "Back") { open("", "") }
                Text(
                    collectionName,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (collectionId.startsWith("artist:")) {
                    val isPinned = collectionId in pinnedArtists
                    IconAction(
                        "pin",
                        if (isPinned) "Unpin artist" else "Pin artist",
                        tint = if (isPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    ) {
                        togglePinArtist(collectionId)
                    }
                }
                if (orderedRows.isNotEmpty() && (collectionId == "liked" || collectionId == "recent" || collections.any { it.id == collectionId })) {
                    IconAction("download", "Export playlist (.m3u8)") {
                        pickers.exportPlaylist(collectionId, collectionName)
                    }
                }
                if (collections.any { it.id == collectionId }) {
                    val isPlaylist = collections.firstOrNull { it.id == collectionId }?.kind == "playlist"
                    if (isPlaylist) {
                        val isPinned = collectionId in pinnedPlaylists
                        IconAction(
                            "pin",
                            if (isPinned) "Unpin playlist" else "Pin playlist",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        ) {
                            togglePinPlaylist(collectionId)
                        }
                    }
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
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolPillButton("Generated", active = albumType == "Generated") { albumType = "Generated" }
                ToolPillButton("Custom", active = albumType == "Custom") { albumType = "Custom" }
            }
        SearchBarField(
            value = query,
            onChange = { query = it },
            placeholder =
                when {
                    showingSongs -> "Search songs"
                    tab == "Folders" -> if (folderPath.isNotEmpty()) "Filter this folder" else "Filter folders"
                    else -> "Find ${tab.lowercase(Locale.ROOT)}"
                },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (showingSongs) {
            if (!selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (collections.any { it.id == collectionId && it.kind in listOf("playlist", "custom") }) {
                        ToolPillButton("Add", "plus") { showSongPicker = true }
                    }
                    ToolPillButton("Select", "list-checks", enabled = rows.isNotEmpty()) { selecting = true }
                    Spacer(Modifier.weight(1f))
                    if (collectionId.isBlank()) {
                        SortControl(
                            value = sort,
                            ascending = sortAsc,
                            options = listOf("Recently added", "Title", "Artist", "Duration"),
                            onPick = { picked ->
                                if (picked != sort) {
                                    sort = picked
                                    sortAsc = picked == "Title" || picked == "Artist"
                                }
                            },
                            onToggleDirection = { sortAsc = !sortAsc },
                        )
                    } else {
                        val isEditableCollection = collections.any { it.id == collectionId && it.kind in listOf("playlist", "custom") }
                        val defaultLabel = if (isEditableCollection) "Playlist order" else "Default order"
                        val currentLabel = if (playlistSort == "Playlist order") defaultLabel else playlistSort
                        SortControl(
                            value = currentLabel,
                            ascending = playlistSortAsc,
                            options = listOf(defaultLabel, "Recently added", "Title", "Artist", "Duration"),
                            onPick = { picked ->
                                val next = if (picked == defaultLabel) "Playlist order" else picked
                                if (next != playlistSort) {
                                    playlistSort = next
                                    playlistSortAsc = next != "Recently added"
                                }
                            },
                            onToggleDirection = { playlistSortAsc = !playlistSortAsc },
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction("x", "Exit selection") { closeSelection() }
                        Text(
                            "${chosen.size} selected",
                            Modifier.weight(1f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Action(
                            if (all) "Deselect all" else "Select all",
                            enabled = rows.isNotEmpty(),
                        ) {
                            selected = if (all) emptyList() else rows.map { it.key }
                        }
                        Box {
                            Action(
                                "Actions",
                                "ellipsis",
                                enabled = chosen.isNotEmpty(),
                            ) {
                                moreSelectionMenu = true
                            }
                            DropdownMenu(moreSelectionMenu, { moreSelectionMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Play next") },
                                    leadingIcon = { Glyph("skip-forward") },
                                    onClick = {
                                        moreSelectionMenu = false
                                        g.engine.value?.addQueue(chosen.map { it.track }, true)
                                        g.message.value = "Added ${chosen.size} to play next"
                                        closeSelection()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to queue") },
                                    leadingIcon = { Glyph("list-music") },
                                    onClick = {
                                        moreSelectionMenu = false
                                        g.engine.value?.addQueue(chosen.map { it.track }, false)
                                        g.message.value = "Added ${chosen.size} to queue"
                                        closeSelection()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to playlist") },
                                    leadingIcon = { Glyph("plus") },
                                    onClick = {
                                        moreSelectionMenu = false
                                        addTo = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit tags") },
                                    leadingIcon = { Glyph("pencil") },
                                    onClick = {
                                        moreSelectionMenu = false
                                        batchArtist = ""
                                        batchAlbum = ""
                                        batchAlbumArtist = ""
                                        batchCover = ""
                                        batchEdit = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clean titles") },
                                    leadingIcon = { Glyph("check") },
                                    onClick = {
                                        moreSelectionMenu = false
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
                                    },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                if (collectionId == "liked" && chosen.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from Liked Songs") },
                                        leadingIcon = { Glyph("x") },
                                        onClick = {
                                            moreSelectionMenu = false
                                            val idsToUnlike = chosen.map { it.track.id }.distinct()
                                            g.task {
                                                val removed = g.library.unlikeAll(idsToUnlike)
                                                g.message.value = "Removed $removed from Liked Songs"
                                            }
                                            closeSelection()
                                        },
                                    )
                                }
                                if (collectionId.isNotBlank() && chosen.any { it.member != null }) {
                                    val removeBulkLabel =
                                        if (collectionById[collectionId]?.kind == "custom") "Remove from album"
                                        else "Remove from playlist"
                                    DropdownMenuItem(
                                        text = { Text(removeBulkLabel) },
                                        leadingIcon = { Glyph("x") },
                                        onClick = {
                                            moreSelectionMenu = false
                                            g.task {
                                                chosen
                                                    .mapNotNull { it.member }
                                                    .forEach { g.library.removeMember(it.id) }
                                            }
                                            closeSelection()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Glyph("trash-2", tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        moreSelectionMenu = false
                                        trash = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
            val canDragReorder = editing && query.isBlank() && playlistSort == "Playlist order" && playlistSortAsc && collectionId.isNotBlank() && collections.any { it.id == collectionId }
            var liveOrder by remember(rows, canDragReorder) { mutableStateOf(rows) }
            val displayedRows = if (canDragReorder) liveOrder else rows
            // fresh list state per sort key keeps the view at the top instead of following item 0 to the bottom
            val songListState = rememberSaveable(tab, collectionId, rowsState.first, saver = LazyListState.Saver) { LazyListState() }
            val reorderState =
                remember(songListState, scope, collectionId) {
                    DragReorderState(
                        listState = songListState,
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
            val latestDisplayedRows by rememberUpdatedState(displayedRows)
            val latestSelectedList by rememberUpdatedState(selected)
            val latestSelecting by rememberUpdatedState(selecting)
            val haptic = LocalHapticFeedback.current
            val railWidthPx = with(LocalDensity.current) { 56.dp.toPx() }
            val edgeScrollPx = with(LocalDensity.current) { 64.dp.toPx() }
            LazyColumn(
                Modifier.weight(1f)
                    .pointerInput(rowsState.first, editing) {
                        if (editing) return@pointerInput
                        coroutineScope {
                            // run on Initial pass and eat the final UP so LazyColumn scroll and row clicks dont fight drag-select
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                fun rowIndexAt(y: Float): Int? {
                                    val visible = songListState.layoutInfo.visibleItemsInfo.filter { it.index in latestDisplayedRows.indices }
                                    if (visible.isEmpty()) return null
                                    return visible.firstOrNull { y >= it.offset && y <= it.offset + it.size }?.index
                                        ?: if (y < visible.first().offset) visible.first().index else visible.last().index
                                }
                                val startIdx = rowIndexAt(down.position.y) ?: return@awaitEachGesture
                                val slop = viewConfiguration.touchSlop
                                val engagedByRail = latestSelecting && down.position.x <= railWidthPx
                                var pointerY = down.position.y
                                val triggered =
                                    if (engagedByRail) {
                                        var started = false
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            val dx = change.position.x - down.position.x
                                            val dy = change.position.y - down.position.y
                                            if (abs(dy) >= slop && abs(dy) >= abs(dx)) {
                                                change.consume()
                                                pointerY = change.position.y
                                                started = true
                                                break
                                            }
                                            if (abs(dx) >= slop) break
                                        }
                                        started
                                    } else {
                                        var held = false
                                        val cancelled =
                                            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                                    if (!change.pressed) return@withTimeoutOrNull true
                                                    val dist = hypot(
                                                        (change.position.x - down.position.x).toDouble(),
                                                        (change.position.y - down.position.y).toDouble(),
                                                    )
                                                    if (dist > slop) return@withTimeoutOrNull true
                                                }
                                            }
                                        if (cancelled == null) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            held = true
                                        }
                                        held
                                    }
                                if (!triggered) return@awaitEachGesture
                                val startRow = latestDisplayedRows.getOrNull(startIdx) ?: return@awaitEachGesture
                                selecting = true
                                val base = latestSelectedList.toHashSet()
                                val fillMode = startRow.key !in base
                                fun applyRange(endIdx: Int) {
                                    val lo = minOf(startIdx, endIdx).coerceAtLeast(0)
                                    val hi = maxOf(startIdx, endIdx).coerceAtMost(latestDisplayedRows.lastIndex)
                                    val next = LinkedHashSet(base)
                                    for (i in lo..hi) {
                                        val k = latestDisplayedRows[i].key
                                        if (fillMode) next += k else next -= k
                                    }
                                    selected = next.toList()
                                }
                                applyRange(startIdx)
                                rowIndexAt(pointerY)?.let(::applyRange)
                                val autoScrollJob =
                                    launch {
                                        while (isActive) {
                                            val info = songListState.layoutInfo
                                            val topEdge = info.viewportStartOffset + edgeScrollPx
                                            val bottomEdge = info.viewportEndOffset - edgeScrollPx
                                            val speed =
                                                when {
                                                    pointerY < topEdge -> -((topEdge - pointerY) / edgeScrollPx).coerceIn(0.2f, 1.5f) * 26f
                                                    pointerY > bottomEdge -> ((pointerY - bottomEdge) / edgeScrollPx).coerceIn(0.2f, 1.5f) * 26f
                                                    else -> 0f
                                                }
                                            if (speed != 0f) {
                                                songListState.scrollBy(speed)
                                                rowIndexAt(pointerY)?.let(::applyRange)
                                            }
                                            delay(16)
                                        }
                                    }
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                        pointerY = change.position.y
                                        rowIndexAt(pointerY)?.let(::applyRange)
                                    }
                                } finally {
                                    autoScrollJob.cancel()
                                }
                            }
                        }
                    },
                state = songListState,
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(displayedRows, key = { _, row -> row.key }) { index, row ->
                    val dragging = canDragReorder && reorderState.draggedKey == row.key
                    val removeLabel =
                        when {
                            collectionId == "liked" -> "Remove from Liked Songs"
                            collectionById[collectionId]?.kind == "custom" -> "Remove from album"
                            else -> "Remove from playlist"
                        }
                    val removeAction: (() -> Unit)? =
                        when {
                            row.member != null -> {
                                val memberId = row.member.id
                                {
                                    g.task {
                                        g.library.removeMember(memberId)
                                        g.message.value =
                                            if (collectionById[collectionId]?.kind == "custom") "Removed from album"
                                            else "Removed from playlist"
                                    }
                                }
                            }
                            collectionId == "liked" -> {
                                val trackId = row.track.id
                                {
                                    g.task {
                                        g.library.unlikeAll(listOf(trackId))
                                        g.message.value = "Removed from Liked Songs"
                                    }
                                }
                            }
                            else -> null
                        }
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
                        enableLongClick = false,
                        onSelect = {
                            selected =
                                if (row.key in selectedKeys) selected - row.key else selected + row.key
                        },
                        onLong = {},
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
                        removeFromCollectionLabel = removeLabel,
                        onRemoveFromCollection = removeAction,
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
        } else if (tab == "Folders") {
            val folderData by produceState(
                FolderViewData(), tracks, roots, folderPath,
            ) {
                value = withContext(Dispatchers.Default) {
                    buildAndroidFolderView(tracks, roots, folderPath)
                }
            }
            val visibleSubfolders = remember(folderData.folders, query) {
                if (query.isBlank()) folderData.folders
                else folderData.folders.filter { it.name.contains(query, true) }
            }
            val visibleFolderTracks = remember(folderData.tracks, query) {
                if (query.isBlank()) folderData.tracks
                else folderData.tracks.filter {
                    it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
                }
            }
            val folderListState = rememberSaveable(folderPath, saver = LazyListState.Saver) { LazyListState() }
            if (folderPath.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconAction("arrow-left", "Up one folder") {
                        folderPath = folderData.parentPath
                        query = ""
                    }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for ((idx, crumb) in folderData.breadcrumbs.withIndex()) {
                            if (idx > 0) {
                                Glyph("chevron-right", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val currentCrumb = idx == folderData.breadcrumbs.lastIndex
                            Text(
                                crumb.name,
                                fontSize = 13.sp,
                                fontWeight = if (currentCrumb) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (currentCrumb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        folderPath = crumb.path
                                        query = ""
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    if (folderData.allTracks.isNotEmpty()) {
                        ToolPillButton("Play all", "play") {
                            g.engine.value?.playTrack(
                                folderData.allTracks.first(),
                                folderData.allTracks,
                                "Folder: ${folderData.name}",
                                0,
                            )
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                state = folderListState,
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(visibleSubfolders, key = { "folder:${it.path}" }) { sub ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                folderPath = sub.path
                                query = ""
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Glyph("folder", tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                sub.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val subLabel =
                                buildString {
                                    if (sub.subfolderCount > 0) {
                                        append(if (sub.subfolderCount == 1) "1 folder · " else "${sub.subfolderCount} folders · ")
                                    }
                                    append(if (sub.trackCount == 1) "1 song" else "${sub.trackCount} songs")
                                }
                            Text(
                                subLabel,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Glyph("chevron-right", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(visibleFolderTracks, key = { _, t -> "ftrack:${t.id}" }) { index, track ->
                    TrackListRow(
                        track = track,
                        onPlay = {
                            g.engine.value?.playTrack(
                                track,
                                visibleFolderTracks,
                                "Folder: ${folderData.name}",
                                index,
                            )
                        },
                        onExpand = {
                            g.engine.value?.playTrack(
                                track,
                                visibleFolderTracks,
                                "Folder: ${folderData.name}",
                                index,
                            )
                            onExpand()
                        },
                        onAction = { onAction(track, it) },
                    )
                }
                if (visibleSubfolders.isEmpty() && visibleFolderTracks.isEmpty()) {
                    item {
                        Hint(
                            if (query.isNotBlank()) "No matching folders or songs"
                            else "Add a music folder in Settings to browse by directory."
                        )
                    }
                }
            }
        } else {
            val groupsState by produceState<Pair<String, List<Triple<String, String, List<Track>>>>>(
                "" to emptyList(), tab, albumType, tracks, membersByCollection, collections, likes, recent, artistSort, artistSortAsc, pinnedPlaylists, pinnedArtists,
            ) {
                value = withContext(Dispatchers.Default) {
                val computed = when (tab) {
                    "Artists" -> {
                        val buckets = LinkedHashMap<String, Pair<String, MutableList<Track>>>()
                        for (track in tracks) {
                            for (artistName in splitArtists(track.artist)) {
                                val key = artistName.lowercase(Locale.ROOT)
                                val entry = buckets.getOrPut(key) { artistName to mutableListOf() }
                                entry.second += track
                            }
                        }
                        val listItems =
                            buckets.map { (key, entry) ->
                                Triple("artist:$key", entry.first, entry.second.toList())
                            }
                        val baseComp: Comparator<Triple<String, String, List<Track>>> =
                            when (artistSort) {
                                "Songs" ->
                                    if (artistSortAsc) compareBy<Triple<String, String, List<Track>>> { it.third.size }.thenBy { it.second.lowercase(Locale.ROOT) }
                                    else compareByDescending<Triple<String, String, List<Track>>> { it.third.size }.thenBy { it.second.lowercase(Locale.ROOT) }
                                else ->
                                    if (artistSortAsc) compareBy { it.second.lowercase(Locale.ROOT) }
                                    else compareByDescending { it.second.lowercase(Locale.ROOT) }
                            }
                        listItems.sortedWith(
                            compareByDescending<Triple<String, String, List<Track>>> { it.first in pinnedArtists }
                                .then(baseComp)
                        )
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
                            // group generated albums by album title so featuring artists dont create duplicate albums
                            tracks
                                .filter { it.album.isNotBlank() }
                                .groupBy { it.album.trim().lowercase(Locale.ROOT) }
                                .map { (key, values) ->
                                    Triple("album:$key", values.first().album.trim(), values)
                                }
                                .sortedBy { it.second.lowercase(Locale.ROOT) }
                    else -> {
                        // pinned playlists sit above utility actions and unpinned playlists below them
                        collections
                            .filter { it.kind == "playlist" }
                            .sortedByDescending { it.id in pinnedPlaylists }
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
                "$tab|$albumType|$artistSort|$artistSortAsc" to computed
                }
            }
            val groups = groupsState.second
            val collectionListState = rememberSaveable(tab, albumType, groupsState.first, saver = LazyListState.Saver) { LazyListState() }
            if (tab == "Artists") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${groups.size} artists",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    SortControl(
                        value = artistSort,
                        ascending = artistSortAsc,
                        options = listOf("Name", "Songs"),
                        onPick = { picked ->
                            if (picked != artistSort) {
                                artistSort = picked
                                artistSortAsc = picked == "Name"
                            }
                        },
                        onToggleDirection = { artistSortAsc = !artistSortAsc },
                    )
                }
            }
            LazyColumn(Modifier.weight(1f), state = collectionListState) {
                if (tab == "Playlists") {
                    val pinnedItems = groups.filter { it.first in pinnedPlaylists && (query.isBlank() || it.second.contains(query, true)) }
                    items(pinnedItems, key = { "pinned:${it.first}" }) { (id, title, values) ->
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
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        title,
                                        Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp,
                                    )
                                    Glyph("pin", "Pinned", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    if (values.size == 1) "1 song" else "${values.size} songs",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (collection != null) {
                                IconAction("ellipsis", "Collection actions") {
                                    collectionMenu = collection
                                }
                            }
                        }
                    }
                }
                if ((tab == "Playlists" || tab == "Albums" && albumType == "Custom") && query.isBlank())
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    name = ""
                                    create = true
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph("plus", tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (tab == "Playlists") "New Playlist" else "New Album",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                if (tab == "Playlists" && query.isBlank())
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { pickers.importPlaylist() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph("download", tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Import Playlist (.m3u / .m3u8)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "Match songs from your library",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                if (tab == "Playlists") {
                    val systemGroups =
                        listOf(
                            Triple("liked", "Liked Songs", tracks.filter { it.id in likes }),
                            Triple("recent", "Recently Played", recent.mapNotNull { trackById[it] }),
                        ).filter { query.isBlank() || it.second.contains(query, true) }
                    items(systemGroups, key = { "sys:${it.first}" }) { (id, title, values) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { open(id, title) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(values.firstOrNull { it.art.isNotBlank() }?.art ?: "")
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(
                                    if (values.size == 1) "1 song" else "${values.size} songs",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                val visibleGroups =
                    groups.filter {
                        (tab != "Playlists" || it.first !in pinnedPlaylists) &&
                            (query.isBlank() || it.second.contains(query, true))
                    }
                val hasPlaylistMatches =
                    tab == "Playlists" &&
                        (groups.any { it.first in pinnedPlaylists && (query.isBlank() || it.second.contains(query, true)) } ||
                            listOf("Liked Songs", "Recently Played").any { query.isBlank() || it.contains(query, true) } ||
                            visibleGroups.isNotEmpty())
                items(
                    visibleGroups,
                    key = { it.first },
                ) { (id, title, values) ->
                    val collection = collectionById[id]
                    val isPinned =
                        (collection?.kind == "playlist" && id in pinnedPlaylists) ||
                            (id.startsWith("artist:") && id in pinnedArtists)
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    title,
                                    Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp,
                                )
                                if (isPinned) {
                                    Glyph("pin", "Pinned", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
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
                        else if (id.startsWith("artist:"))
                            IconAction("ellipsis", "Artist actions") {
                                artistMenu = id to title
                            }
                    }
                }
                if (query.isNotBlank() && ((tab == "Playlists" && !hasPlaylistMatches) || (tab != "Playlists" && visibleGroups.isEmpty()))) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            Hint(
                                when (tab) {
                                    "Playlists" -> "No matching playlists"
                                    "Artists" -> "No matching artists"
                                    else -> "No matching albums"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (create || rename != null)
        AppModalDialog(
            onDismissRequest = {
                create = false
                rename = null
            },
            title =
                if (rename != null) "Edit collection"
                else if (tab == "Playlists") "New Playlist" else "New Album",
            icon = if (rename != null) "pencil" else "plus",
            actions = {
                ModalTonalButton("Cancel") {
                    create = false
                    rename = null
                }
                ModalPrimaryButton("Save", icon = "check", enabled = name.isNotBlank()) {
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
        ) {
            Input(name, { name = it }, "Collection name")
        }
    collectionMenu?.let { c ->
        val isCustom = c.kind == "custom"
        val kindWord = if (isCustom) "album" else "playlist"
        val pinned = !isCustom && c.id in pinnedPlaylists
        val cMembers = membersByCollection[c.id].orEmpty()
        val dupCount =
            remember(cMembers) {
                val seen = HashSet<String>()
                cMembers.count { !seen.add(it.track) }
            }
        AppModalDialog(
            onDismissRequest = { collectionMenu = null },
            title = c.name,
            subtitle = "${cMembers.size} ${if (cMembers.size == 1) "song" else "songs"}",
            icon = "list-music",
            actions = {
                ModalTonalButton("Close") { collectionMenu = null }
            },
        ) {
            if (!isCustom) {
                ModalOptionRow(
                    title = if (pinned) "Unpin playlist" else "Pin playlist",
                    subtitle = if (pinned) "Remove from top of Playlists" else "Keep at top of Playlists",
                    icon = "pin",
                    selected = pinned,
                ) {
                    togglePinPlaylist(c.id)
                    collectionMenu = null
                }
            }
            ModalOptionRow(
                title = "Edit $kindWord name",
                icon = "pencil",
            ) {
                name = c.name
                rename = c
                collectionMenu = null
            }
            ModalOptionRow(
                title = "Change cover artwork",
                icon = "image",
            ) {
                collectionMenu = null
                pickers.image { uri ->
                    g.task { g.library.collection(c.name, c.kind, c.id, uri) }
                }
            }
            ModalOptionRow(
                title = "Export $kindWord (.m3u8)",
                icon = "download",
            ) {
                collectionMenu = null
                pickers.exportPlaylist(c.id, c.name)
            }
            ModalOptionRow(
                title = "Remove duplicate songs",
                subtitle =
                    if (dupCount > 0) "$dupCount duplicate ${if (dupCount == 1) "entry" else "entries"} found"
                    else "No duplicate songs in this $kindWord",
                icon = "list-checks",
            ) {
                collectionMenu = null
                if (dupCount > 0) removeDuplicatesTarget = c to dupCount
                else g.message.value = "No duplicate songs in ${c.name}"
            }
            ModalOptionRow(
                title = "Delete $kindWord",
                subtitle = "Audio files in storage are kept",
                icon = "trash-2",
                danger = true,
            ) {
                delete = c
                collectionMenu = null
            }
        }
    }
    removeDuplicatesTarget?.let { (c, count) ->
        val kindWord = if (c.kind == "custom") "album" else "playlist"
        Confirmation(
            "Remove $count ${if (count == 1) "duplicate song" else "duplicate songs"}?",
            "Keeps the first occurrence of each song in \"${c.name}\" in $kindWord order.",
            "Remove duplicates",
            { removeDuplicatesTarget = null },
            danger = true,
            icon = "list-checks",
        ) {
            g.task {
                val removed = g.library.removeDuplicates(c.id)
                g.message.value = "Removed $removed duplicate ${if (removed == 1) "song" else "songs"}"
            }
        }
    }
    artistMenu?.let { (artistId, artistName) ->
        val pinned = artistId in pinnedArtists
        AppModalDialog(
            onDismissRequest = { artistMenu = null },
            title = artistName,
            subtitle = "Artist actions",
            icon = "music-2",
            actions = { ModalTonalButton("Close") { artistMenu = null } },
        ) {
            ModalOptionRow(
                title = if (pinned) "Unpin artist" else "Pin artist",
                subtitle = if (pinned) "Remove from top of Artists" else "Keep at top of Artists",
                icon = "pin",
                selected = pinned,
            ) {
                togglePinArtist(artistId)
                artistMenu = null
            }
        }
    }
    delete?.let { c ->
        Confirmation("Delete ${c.name}?", "Audio files are kept.", "Delete", { delete = null }) {
            g.task {
                g.library.deleteCollection(c.id)
                if (c.id in pinnedPlaylists) {
                    g.prefs.update { it.copy(pinnedPlaylists = it.pinnedPlaylists - c.id) }
                }
            }
            if (collectionId == c.id) open("", "")
        }
    }
    if (addTo)
        PlaylistChooser(g, actualIds, { addTo = false }) { collection ->
            val idsToAdd = actualIds.toList()
            addTo = false
            closeSelection()
            g.task {
                val res = g.library.addToCollection(collection, idsToAdd)
                val target = if (collection == "liked") "Liked Songs" else "playlist"
                g.message.value =
                    when {
                        res.added > 0 && res.skipped > 0 -> "Added ${res.added} · ${res.skipped} already in $target"
                        res.added > 0 -> "Added ${res.added} ${if (res.added == 1) "song" else "songs"} to $target"
                        res.skipped > 0 -> "Already in $target (${res.skipped} skipped)"
                        else -> "No songs added"
                    }
            }
        }
    if (showSongPicker && collections.any { it.id == collectionId && it.kind in listOf("playlist", "custom") })
        PlaylistSongPicker(tracks, { showSongPicker = false }) { ids ->
            val target = collectionId
            showSongPicker = false
            g.task {
                val res = g.library.addToCollection(target, ids)
                g.message.value =
                    when {
                        res.added > 0 && res.skipped > 0 -> "Added ${res.added} · ${res.skipped} already in playlist"
                        res.added > 0 -> "Added ${res.added} ${if (res.added == 1) "song" else "songs"}"
                        res.skipped > 0 -> "Already in this playlist (${res.skipped} skipped)"
                        else -> "No songs added"
                    }
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
        AppModalDialog(
            onDismissRequest = { batchEdit = false },
            title = "Batch edit tags",
            subtitle = "${actualIds.distinct().size} selected · blank fields keep existing tags",
            icon = "pencil",
            actions = {
                ModalTonalButton("Cancel") { batchEdit = false }
                ModalPrimaryButton(
                    "Apply",
                    icon = "check",
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
        ) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Input(batchArtist, { batchArtist = it }, "Artist / creator")
                Input(batchAlbum, { batchAlbum = it }, "Album")
                Input(batchAlbumArtist, { batchAlbumArtist = it }, "Album artist")
                ModalOptionRow(
                    title = if (batchCover.isNotBlank()) "Artwork selected" else "Set shared cover",
                    subtitle = if (batchCover.isNotBlank()) "Tap to replace selected image" else "Choose an image for all selected songs",
                    icon = "image",
                    selected = batchCover.isNotBlank(),
                ) {
                    pickers.image { batchCover = it }
                }
            }
        }
    cleanPreview?.let { changes ->
        AppModalDialog(
            onDismissRequest = { cleanPreview = null },
            title = "Clean ${changes.size} song titles?",
            subtitle = "Strips (Official Audio), (Official Video), [Lyrics], etc.",
            icon = "check",
            actions = {
                ModalTonalButton("Cancel") { cleanPreview = null }
                ModalPrimaryButton("Clean titles", icon = "check") {
                    val ids = changes.map { it.first }
                    cleanPreview = null
                    closeSelection()
                    g.task { g.message.value = g.library.cleanTitles(ids) }
                }
            },
        ) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((_, before, after) in changes.take(20)) {
                    ModalSectionCard {
                        Text(before, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ $after", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
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
    enableLongClick: Boolean = true,
    onSelect: () -> Unit = {},
    onLong: () -> Unit = {},
    onPlay: () -> Unit,
    onExpand: () -> Unit,
    onAction: (String) -> Unit,
    onStep: (Int) -> Unit = {},
    removeFromCollectionLabel: String = "Remove from playlist",
    onRemoveFromCollection: (() -> Unit)? = null,
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
                onLongClick = if (enableLongClick) onLong else null,
            )
            .padding(start = 12.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(selected, if (enableLongClick) ({ onSelect() }) else null, Modifier.size(32.dp))
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
                    )) DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Glyph(icon) },
                    onClick = {
                        menu = false
                        onAction(action)
                    },
                )
                if (onRemoveFromCollection != null) {
                    DropdownMenuItem(
                        text = { Text(removeFromCollectionLabel) },
                        leadingIcon = { Glyph("x") },
                        onClick = {
                            menu = false
                            onRemoveFromCollection()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Glyph("trash-2", tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onAction("trash")
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistChooser(
    g: Graph,
    ids: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val collections by g.library.collections.collectAsStateWithLifecycle()
    val playlistMembers by g.library.members.collectAsStateWithLifecycle()
    val libraryTracks by g.library.tracks.collectAsStateWithLifecycle()
    val likes by g.library.likes.collectAsStateWithLifecycle(emptySet())
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val pinnedPlaylists = remember(prefs.pinnedPlaylists) { prefs.pinnedPlaylists.toSet() }
    val covers = remember(libraryTracks) { libraryTracks.associate { it.id to it.art } }
    val membersByCollection = remember(playlistMembers) { playlistMembers.groupBy { it.collection } }
    var search by rememberSaveable { mutableStateOf("") }
    var confirmName by remember { mutableStateOf("") }
    var enteringCustomName by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    val pinnedEditable =
        remember(collections, pinnedPlaylists, search) {
            collections
                .filter { it.kind in listOf("playlist", "custom") && it.id in pinnedPlaylists && (search.isBlank() || it.name.contains(search, true)) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    val unpinnedEditable =
        remember(collections, pinnedPlaylists, search) {
            collections
                .filter { it.kind in listOf("playlist", "custom") && it.id !in pinnedPlaylists && (search.isBlank() || it.name.contains(search, true)) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    val showLiked = search.isBlank() || "liked songs".contains(search, true)
    val singleTrackId = ids.singleOrNull()
    AppModalDialog(
        onDismissRequest = onDismiss,
        title = "Add to playlist",
        subtitle = "${ids.size} ${if (ids.size == 1) "song" else "songs"} selected",
        icon = "list-music",
        actions = { ModalTonalButton("Close", onClick = onDismiss) },
    ) {
        SearchBarField(
            value = search,
            onChange = {
                search = it
                if (confirmName.isNotEmpty()) confirmName = ""
            },
            placeholder = "Find or name a playlist",
        )
        when {
            confirmName.isNotBlank() -> {
                ModalSectionCard(highlighted = true) {
                    Text(
                        "Create playlist \"$confirmName\" and add ${ids.size} ${if (ids.size == 1) "song" else "songs"}?",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModalTonalButton("Edit") {
                            customName = confirmName
                            confirmName = ""
                            enteringCustomName = true
                        }
                        ModalTonalButton("Cancel") { confirmName = "" }
                        ModalPrimaryButton("Create & add", icon = "check") {
                            val target = confirmName
                            confirmName = ""
                            g.task {
                                val id = g.library.collection(target)
                                withContext(Dispatchers.Main) { onPick(id) }
                            }
                        }
                    }
                }
            }
            enteringCustomName -> {
                ModalSectionCard {
                    Input(customName, { customName = it }, "New playlist name")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        ModalTonalButton("Cancel") { enteringCustomName = false }
                        ModalPrimaryButton("Create & add", icon = "check", enabled = customName.isNotBlank()) {
                            val target = customName.trim()
                            enteringCustomName = false
                            g.task {
                                val id = g.library.collection(target)
                                withContext(Dispatchers.Main) { onPick(id) }
                            }
                        }
                    }
                }
            }
            else -> {
                val typed = search.trim()
                ModalOptionRow(
                    title = if (typed.isNotEmpty()) "Create playlist \"$typed\"" else "Create playlist",
                    subtitle = if (typed.isNotEmpty()) "Use this name & add selected songs" else "Enter a new playlist name",
                    icon = "plus",
                    selected = typed.isNotEmpty(),
                ) {
                    if (typed.isNotEmpty()) confirmName = typed
                    else {
                        customName = ""
                        enteringCustomName = true
                    }
                }
            }
        }
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            @Composable
            fun playlistRow(c: dev.sillyplayer.data.Collection, pinned: Boolean) {
                val entries = membersByCollection[c.id].orEmpty()
                val already = singleTrackId != null && entries.any { it.track == singleTrackId }
                val cover = c.art.ifBlank { entries.firstNotNullOfOrNull { covers[it.track]?.takeIf(String::isNotBlank) }.orEmpty() }
                Surface(
                    onClick = { onPick(c.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(cover, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(c.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                if (pinned) Glyph("pin", "Pinned", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                (if (entries.size == 1) "1 song" else "${entries.size} songs") + if (already) " · Already added" else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Glyph("chevron-right", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            for (c in pinnedEditable) playlistRow(c, true)
            if (showLiked) {
                val alreadyLiked = singleTrackId != null && singleTrackId in likes
                ModalOptionRow(
                    title = "Liked Songs",
                    subtitle = (if (likes.size == 1) "1 song" else "${likes.size} songs") + if (alreadyLiked) " · Already added" else "",
                    icon = "heart",
                    trailingIcon = "chevron-right",
                ) {
                    onPick("liked")
                }
            }
            for (c in unpinnedEditable) playlistRow(c, false)
            if (pinnedEditable.isEmpty() && unpinnedEditable.isEmpty() && !showLiked) {
                Hint("No matching playlists")
            }
        }
    }
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
    AppModalDialog(
        onDismissRequest = onDismiss,
        title = "Add songs",
        subtitle = "${selected.size} ${if (selected.size == 1) "song" else "songs"} selected",
        icon = "plus",
        actions = {
            ModalTonalButton("Cancel", onClick = onDismiss)
            ModalPrimaryButton("Add (${selected.size})", icon = "check", enabled = selected.isNotEmpty()) {
                onAdd(tracks.asSequence().filter { it.id in selectedIds }.map { it.id }.toList())
            }
        },
    ) {
        SearchBarField(query, { query = it }, "Search library")
        if (filtered.isEmpty()) Hint(if (tracks.isEmpty()) "Your library has no songs yet" else "No matching songs")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.id }) { track ->
                val isChecked = track.id in selectedIds
                Surface(
                    onClick = {
                        selected = if (isChecked) selected - track.id else selected + track.id
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(isChecked, onCheckedChange = null)
                        Spacer(Modifier.width(4.dp))
                        Artwork(track.art, 42)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                track.artist + if (!track.available) " · Unavailable" else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
