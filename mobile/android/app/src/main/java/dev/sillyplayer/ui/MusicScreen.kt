package dev.sillyplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
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
    var artistMenu by remember { mutableStateOf<Pair<String, String>?>(null) }
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
        "" to emptyList(), tracks, members, likes, recent, collectionId, sort, sortAsc,
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
                        val targetAlbum = collectionId.removePrefix("album:").substringAfter('|').trim().lowercase(Locale.ROOT)
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
                if (collectionId.isNotBlank()) values
                else
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
            "$collectionId|$sort|$sortAsc" to ordered
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
                                if (collectionId.isNotBlank() && chosen.any { it.member != null }) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from playlist") },
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
            val canDragReorder = editing && query.isBlank() && collectionId.isNotBlank() && collections.any { it.id == collectionId }
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
            LazyColumn(
                Modifier.weight(1f),
                state = songListState,
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
                        val playlists =
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
                        listOf(
                            Triple("liked", "Liked Songs", tracks.filter { it.id in likes }),
                            Triple("recent", "Recently Played", recent.mapNotNull { trackById[it] }),
                        ) + playlists
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
                if (tab == "Playlists" || tab == "Albums" && albumType == "Custom")
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
                if (tab == "Playlists")
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
                items(
                    groups.filter { query.isBlank() || it.second.contains(query, true) },
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
        val pinned = c.kind == "playlist" && c.id in pinnedPlaylists
        AlertDialog(
            onDismissRequest = { collectionMenu = null },
            title = { Text(c.name) },
            text = {
                Column {
                    if (c.kind == "playlist") {
                        Action(if (pinned) "Unpin playlist" else "Pin playlist", "pin") {
                            togglePinPlaylist(c.id)
                            collectionMenu = null
                        }
                    }
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
    artistMenu?.let { (artistId, artistName) ->
        val pinned = artistId in pinnedArtists
        AlertDialog(
            onDismissRequest = { artistMenu = null },
            title = { Text(artistName) },
            text = {
                Column {
                    Action(if (pinned) "Unpin artist" else "Pin artist", "pin") {
                        togglePinArtist(artistId)
                        artistMenu = null
                    }
                }
            },
            confirmButton = { Action("Close") { artistMenu = null } },
        )
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
