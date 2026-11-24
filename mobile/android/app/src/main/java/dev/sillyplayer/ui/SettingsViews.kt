package dev.sillyplayer.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sillyplayer.Graph
import dev.sillyplayer.data.*
import java.util.Locale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
private fun SettingsCard(
    title: String = "",
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsAction(
    label: String,
    icon: String = "",
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, contentPadding = padding) {
            if (icon.isNotBlank()) {
                Glyph(icon, modifier = Modifier.size(16.dp), tint = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = padding,
            colors =
                if (danger)
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                else ButtonDefaults.filledTonalButtonColors(),
        ) {
            if (icon.isNotBlank()) {
                Glyph(icon, modifier = Modifier.size(16.dp), tint = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(g: Graph, pickers: Pickers) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(category != null) { category = null }
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val roots by g.library.roots.collectAsStateWithLifecycle()
    val status by g.library.status.collectAsStateWithLifecycle()
    val backup by g.backup.state.collectAsStateWithLifecycle()
    val sync by g.sync.state.collectAsStateWithLifecycle()
    val resetting by g.resetting.collectAsStateWithLifecycle()
    val trash by g.library.trash.collectAsStateWithLifecycle()
    val playbackError by g.playbackError.collectAsStateWithLifecycle()
    var showPlaybackError by remember { mutableStateOf(false) }
    var clearFolder by remember { mutableStateOf(false) }
    if (showPlaybackError)
        AlertDialog(
            onDismissRequest = { showPlaybackError = false },
            title = { Text("Playback error details") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        playbackError,
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaybackError = false }) { Text("Close") }
            },
        )
    var sleep by remember { mutableStateOf(false) }
    var eq by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf<List<Track>?>(null) }
    var duplicates by remember { mutableStateOf<List<List<Track>>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var emptyTrash by remember { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var restoreInfo by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (category == null) {
            Text(
                "Settings",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val categories =
                listOf(
                    Triple("Appearance", "settings", "Palette and light or dark mode"),
                    Triple("Playback", "play", "Equalizer, sleep timer, fades and audio ducking"),
                    Triple("Library and storage", "folder", "Music folders, database backup and Trash"),
                    Triple("Phone sync", "rotate-ccw", "Wi-Fi sync, QR pairing and conflicts"),
                    Triple("Backup and restore", "download", "Export and restore portable ZIP archives"),
                    Triple("Permissions", "info", "Storage and notification permissions"),
                    Triple("About", "info", "Version, licenses and developer tools"),
                )
            for ((name, icon, desc) in categories) {
                Surface(
                    onClick = { category = name },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Glyph(icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Glyph("arrow-right", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconAction("arrow-left", "Back to Settings") { category = null }
                Text(category ?: "", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (category == "Permissions") {
            SettingsCard("Permissions", "Required for reading music folders and showing playback controls.") {
                PermissionNotice(pickers)
            }
        }
        if (category == "Appearance") {
            SettingsCard("Theme", "Choose a color palette and light or dark mode.") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PickMenu(prefs.palette, Palettes.keys.toList()) { p ->
                        g.task { g.prefs.update { it.copy(palette = p) } }
                    }
                    PickMenu(if (prefs.light) "Light" else "Dark", listOf("Dark", "Light")) { mode ->
                        g.task { g.prefs.update { it.copy(light = mode == "Light") } }
                    }
                }
            }
        }
        if (category == "Playback") {
            SettingsCard("Audio & timer") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsAction("Equalizer", "settings") { eq = true }
                    SettingsAction(
                        if (prefs.sleepEnd > 0) "Sleep timer active" else "Sleep timer",
                        "clock",
                        primary = prefs.sleepEnd > 0,
                    ) {
                        sleep = true
                    }
                    if (playbackError.isNotBlank())
                        SettingsAction("Last playback error", "info", danger = true) { showPlaybackError = true }
                }
            }
            SettingsCard("Focus & transitions") {
                Choice("Smart shuffle", prefs.smartShuffle) { on ->
                    g.task { g.prefs.update { it.copy(smartShuffle = on) } }
                }
                Hint("Avoids playing the same artist back to back and favors songs you haven't heard recently.")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Choice("Lower volume on notifications (duck)", prefs.duckAudio) { on ->
                    g.task { g.prefs.update { it.copy(duckAudio = on) } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Choice("Fade out on pause", prefs.pauseFade) { on ->
                    g.task { g.prefs.update { it.copy(pauseFade = on) } }
                }
                if (prefs.pauseFade) {
                    var ms by remember(prefs.fadeMs) { mutableFloatStateOf(prefs.fadeMs.toFloat()) }
                    Text("Fade duration · %.2f s".format(ms / 1000), fontSize = 13.sp)
                    Slider(
                        ms,
                        { ms = it },
                        valueRange = 50f..5000f,
                        steps = 98,
                        onValueChangeFinished = {
                            g.task { g.prefs.update { it.copy(fadeMs = ms.toInt()) } }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Choice("Smooth transition fade between songs", prefs.crossfadeMs > 0) { on ->
                    g.task { g.prefs.update { it.copy(crossfadeMs = if (on) 3000 else 0) } }
                }
                if (prefs.crossfadeMs > 0) {
                    var cf by remember(prefs.crossfadeMs) { mutableFloatStateOf(prefs.crossfadeMs.toFloat()) }
                    Text("Transition fade · %.1f s".format(cf / 1000), fontSize = 13.sp)
                    Slider(
                        cf,
                        { cf = it },
                        valueRange = 1000f..8000f,
                        steps = 13,
                        onValueChangeFinished = {
                            g.task { g.prefs.update { it.copy(crossfadeMs = cf.toInt()) } }
                        },
                    )
                }
            }
        }
        if (category == "Library and storage") {
            SettingsCard("Music folders", status) {
                for (root in roots) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            root.name + if (!root.available) " · Unavailable" else "",
                            Modifier.weight(1f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconAction("folder", "Locate moved folder") { pickers.folder("replace:" + root.id) }
                        IconAction("x", "Remove watched folder") { g.task { g.library.removeRoot(root.id) } }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsAction("Add folder", "folder") { pickers.folder("root") }
                    SettingsAction("Add files", "plus") { pickers.files() }
                    SettingsAction("Rescan", "rotate-ccw", !backup.busy) { g.task { g.library.reconcile(true) } }
                }
            }
            val selectedFolderName =
                roots.firstOrNull { it.uri == prefs.musicDir }?.name
                    ?: if (prefs.musicDir.isBlank()) "Choose an existing folder, such as Music"
                    else "Audio folder selected"
            val recoveryStatus by g.library.recovery.status.collectAsStateWithLifecycle()
            val lastSavedAt by g.library.recovery.lastSavedAt.collectAsStateWithLifecycle()
            val savingDb by g.library.recovery.saving.collectAsStateWithLifecycle()
            val lastSavedLabel =
                remember(lastSavedAt) {
                    if (lastSavedAt > 0L)
                        "Database last saved: " +
                            java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT,
                                )
                                .format(java.util.Date(lastSavedAt))
                    else "Database not saved to folder yet"
                }
            val copyingMusic by g.library.copyingMusic.collectAsStateWithLifecycle()
            val copyMusicStatus by g.library.copyMusicStatus.collectAsStateWithLifecycle()
            val clearingFolder by g.library.clearingFolder.collectAsStateWithLifecycle()
            val clearFolderStatus by g.library.clearFolderStatus.collectAsStateWithLifecycle()
            val songs by g.library.tracks.collectAsStateWithLifecycle()
            SettingsCard("Song storage", selectedFolderName) {
                Hint(
                    "Audio files and the SillyPlayer SQLite database (.sillyplayer-library-*.sqlite3) are saved in this folder so your songs, playlists, likes, listening history and settings survive uninstalling."
                )
                Hint(lastSavedLabel)
                Hint(recoveryStatus)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsAction("Choose audio folder", "folder", !clearingFolder) { pickers.folder("destination") }
                    SettingsAction(
                        if (savingDb) "Saving database…" else "Save database now",
                        "download",
                        prefs.musicDir.isNotBlank() && !savingDb && !clearingFolder && !resetting,
                    ) {
                        g.task { g.library.recovery.saveNow(allowEmpty = false) }
                    }
                    SettingsAction(
                        "Clear audio folder",
                        "trash-2",
                        prefs.musicDir.isNotBlank() && !sync.busy && !backup.busy && !copyingMusic && !clearingFolder && !resetting,
                        danger = true,
                    ) {
                        clearFolder = true
                    }
                }
                if (clearingFolder) Progress(clearFolderStatus)
                else Hint(clearFolderStatus)
                if (songs.any { it.uri.startsWith("file:") && "/music/Synced/" in it.uri && !it.deleted })
                    SettingsAction(
                        "Copy app-stored songs to selected folder",
                        "folder",
                        prefs.musicDir.isNotBlank() && !copyingMusic && !clearingFolder && !backup.busy,
                    ) {
                        g.task { g.library.copyAppStoredMusic() }
                    }
                Hint(copyMusicStatus)
            }
            SettingsCard("Library tools") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsAction("Find missing files", "folder", !checking) {
                        checking = true
                        g.task {
                            try {
                                val trashIds = g.dao.trash().mapTo(HashSet()) { it.track }
                                missing =
                                    g.dao
                                        .allTracks()
                                        .filter { !it.deleted || it.id !in trashIds }
                                        .filter {
                                            !it.available ||
                                                runCatching {
                                                        g.input(it.uri).use { stream -> stream.read() }
                                                    }
                                                    .isFailure
                                        }
                            } finally {
                                checking = false
                            }
                        }
                    }
                    SettingsAction("Find exact duplicates", "list-checks", !checking) {
                        checking = true
                        g.task {
                            try {
                                duplicates = g.library.duplicates()
                            } finally {
                                checking = false
                            }
                        }
                    }
                    SettingsAction("Repair downloaded songs", "rotate-ccw", !checking) {
                        checking = true
                        g.task { try { g.online.repairDownloadedSongs() } finally { checking = false } }
                    }
                }
                if (checking) Progress("Checking library")
            }
            SettingsCard("Trash", "${trash.size} files · kept until you empty Trash") {
                for (entry in trash.take(30)) Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.name,
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Action("Restore", "rotate-ccw") { g.task { g.library.restoreTrash(entry) } }
                }
                SettingsAction("Empty Trash", "trash-2", trash.isNotEmpty(), danger = true) { emptyTrash = true }
            }
        }
        if (category == "Backup and restore") {
            SettingsCard("ZIP archive", "Desktop-compatible ZIP: audio, playlists, likes, artwork and song modifiers.") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsAction("Back up library", "download", !backup.busy, primary = true) {
                        g.task { exportPreview = g.backup.preview() }
                    }
                    SettingsAction("Restore backup", "folder", !backup.busy) { restoreInfo = true }
                }
                if (backup.busy) {
                    Progress(backup.stage, backup.progress)
                    SettingsAction("Cancel backup / restore", "x", danger = true) { g.backup.cancel() }
                }
                Hint(backup.message)
                if (backup.omissions.isNotEmpty()) Hint("Omitted: " + backup.omissions.joinToString(", "))
            }
        }
        if (category == "Phone sync") SyncSettings(g, pickers)
        if (category == "About") {
            val update by g.updates.state.collectAsStateWithLifecycle()
            SettingsCard("Updates", "Check GitHub releases for newer versions. Updates are never downloaded automatically.") {
                Choice("Notify about updates", prefs.updateNotifications) { enabled ->
                    g.task { g.prefs.update { it.copy(updateNotifications = enabled) } }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsAction(
                        if (update.checking) "Checking…" else "Check for updates",
                        "rotate-ccw",
                        !update.checking,
                        primary = !update.updateAvailable,
                    ) {
                        g.task { g.updates.check(manual = true) }
                    }
                    if (update.updateAvailable) {
                        SettingsAction("View v${update.latestVersion}", "external-link", primary = true) {
                            g.updates.openReleasePage()
                        }
                    }
                }
                if (update.status.isNotBlank()) Hint(update.status)
            }
            SettingsCard("SillyPlayer ${dev.sillyplayer.BuildConfig.VERSION_NAME}", "Silly Cat Productions") {
                SettingsAction("Third-party licenses", "info") { licenses = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Choice("Developer mode", prefs.developerMode) { enabled ->
                    g.task { g.prefs.update { it.copy(developerMode = enabled) } }
                }
                if (prefs.developerMode) DeveloperSettings(g)
            }
        }
    }
    if (licenses) LicensesDialog(g) { licenses = false }
    if (sleep) SleepDialog(g) { sleep = false }
    if (eq) EqualizerDialog(g) { eq = false }
    if (clearFolder) {
        val folderLabel = roots.firstOrNull { it.uri == prefs.musicDir }?.name ?: "selected audio folder"
        Confirmation(
            "Clear audio folder?",
            "This permanently deletes all audio files (and SillyPlayer Trash) inside \"$folderLabel\" and removes their songs from this phone's library.\n\nDesktop files are not deleted.",
            "Delete all songs",
            { clearFolder = false },
        ) {
            g.task { g.library.clearMusicFolder() }
        }
    }
    if (emptyTrash)
        Confirmation(
            "Empty Trash?",
            "This permanently deletes all ${trash.size} files in SillyPlayer Trash.",
            "Delete permanently",
            { emptyTrash = false },
        ) {
            g.task { g.library.emptyTrash() }
        }
    exportPreview?.let { (count, omissions) ->
        Confirmation(
            "Back up $count songs?",
            if (omissions.isEmpty()) "Choose where to save the ZIP archive."
            else "Unavailable files will be omitted:\n" + omissions.take(15).joinToString("\n"),
            "Continue",
            { exportPreview = null },
        ) {
            pickers.backup()
        }
    }
    if (restoreInfo)
        Confirmation(
            "Restore library backup",
            "Choose a SillyPlayer ZIP. Files and checksums are verified before anything is imported. Existing library edits are kept.",
            "Choose ZIP",
            { restoreInfo = false },
        ) {
            pickers.restore()
        }
    val restorePreview by g.backup.approval.collectAsStateWithLifecycle()
    restorePreview?.let { preview ->
        Confirmation(
            "Restore ${preview.first} songs?",
            "Verified archive · ${preview.second/(1024*1024)} MiB. Merge into the configured music folder without replacing existing edits.",
            "Restore",
            { g.backup.confirmRestore(false) },
        ) {
            g.backup.confirmRestore(true)
        }
    }
    missing?.let { rows ->
        AlertDialog(
            onDismissRequest = { missing = null },
            title = { Text("Missing files") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (rows.isEmpty()) Hint("No missing files found")
                    for (track in rows) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(track.title, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2)
                        Action("Locate", "folder") { pickers.locate(track.id) }
                    }
                }
            },
            confirmButton = { Action("Close") { missing = null } },
        )
    }
    duplicates?.let { groups -> DuplicateDialog(g, groups) { duplicates = null } }
}

@Composable
private fun DuplicateDialog(g: Graph, groups: List<List<Track>>, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val group = groups.getOrNull(index)
    val engine by g.engine.collectAsStateWithLifecycle()
    val playback by
        (engine?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(dev.sillyplayer.media.PlaybackState()) })
            .collectAsStateWithLifecycle()
    val current = playback.music?.id
    var keep by
        remember(group) {
            mutableStateOf(
                group?.firstOrNull { it.id == current }?.id ?: group?.firstOrNull()?.id ?: ""
            )
        }
    var confirming by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exact duplicates") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (group == null) Hint("No remaining duplicate groups")
                else {
                    Hint(
                        "Group ${index+1}/${groups.size}. Choose the copy to keep. Playlist occurrences and likes follow it."
                    )
                    for (track in group) Row(
                        Modifier.fillMaxWidth().clickable(
                            enabled = current == null || group.none { it.id == current }
                        ) {
                            keep = track.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            keep == track.id,
                            {
                                if (current == null || group.none { it.id == current })
                                    keep = track.id
                            },
                        )
                        Column {
                            Text(track.title, fontSize = 13.sp)
                            Hint(track.uri)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (group != null) Action("Consolidate and Trash extras") { confirming = true }
            else Action("Close") { onDismiss() }
        },
        dismissButton = { if (group != null) Action("Skip") { index++ } },
    )
    if (confirming && group != null)
        Confirmation(
            "Consolidate duplicates?",
            "Keep the selected song and move ${group.size-1} extra files to Trash. This does not permanently delete files.",
            "Consolidate",
            { confirming = false },
        ) {
            g.task { g.library.consolidate(keep, group.filter { it.id != keep }.map { it.id }) }
            index++
        }
}

@Composable
private fun SyncSettings(g: Graph, pickers: Pickers) {
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val peers by g.sync.peers.collectAsStateWithLifecycle()
    val sync by g.sync.state.collectAsStateWithLifecycle()
    val conflicts by g.sync.conflicts.collectAsStateWithLifecycle()
    val resolution by g.sync.resolutionSummary.collectAsStateWithLifecycle()
    val songGroups = remember(conflicts) { conflicts.filter { it.entity == "tracks" }.groupBy { it.peer } }
    var conflictPage by remember { mutableIntStateOf(0) }
    val lastConflictPage = (conflicts.size - 1).coerceAtLeast(0) / 20
    val visibleConflictPage = conflictPage.coerceIn(0, lastConflictPage)
    val conflictAnchor = remember { BringIntoViewRequester() }
    val pagerScope = rememberCoroutineScope()
    fun turnConflictPage(target: Int) {
        conflictPage = target.coerceIn(0, lastConflictPage)
        pagerScope.launch {
            withFrameNanos { }
            conflictAnchor.bringIntoView()
        }
    }
    LaunchedEffect(conflicts) { conflictPage = conflictPage.coerceAtMost(lastConflictPage) }
    val pending by g.sync.pendingLink.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<Peer?>(null) }
    var unpair by remember { mutableStateOf<Peer?>(null) }
    LaunchedEffect(pending) { if (pending.isNotBlank()) link = pending }
    val connected by g.sync.connectedPeer.collectAsStateWithLifecycle()
    SettingsCard(
        "Pair desktop",
        if (connected != null) "Connected · shared choices refresh while the app is open."
        else "Enable Phone sync on desktop, then scan its QR code or paste its pairing link.",
    ) {
        if (scanning)
            PairingQrScanner(
                onDismiss = { scanning = false },
                onScanned = { scanned ->
                    scanning = false
                    link = scanned
                    g.sync.pendingLink.value = scanned
                    if (!sync.busy) {
                        g.task {
                            val paired = g.sync.pair(scanned)
                            link = ""
                            choosing = paired
                        }
                    }
                },
            )
        Input(link, { link = it }, "Pairing link")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsAction("Scan QR", "search", primary = true) { scanning = true }
            SettingsAction("Pair desktop", "plus", link.isNotBlank() && !sync.busy) {
                g.task {
                    val paired = g.sync.pair(link)
                    link = ""
                    choosing = paired
                }
            }
        }
        if (sync.busy) {
            Progress(sync.stage, sync.progress)
            SettingsAction("Cancel sync", "x", danger = true) { g.sync.cancel() }
        }
        Hint(sync.message)
        if (resolution.message.isNotBlank() && resolution.message != sync.message) Hint(resolution.message)
    }
    for (peer in peers) {
        SettingsCard(peer.name, peer.host) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsAction("Choose music", "list-music", !sync.busy) { choosing = peer }
                SettingsAction(
                    if (sync.stage == "Paused") "Resume" else "Sync now",
                    "rotate-ccw",
                    !sync.busy,
                    primary = true,
                ) {
                    g.sync.start(peer)
                }
                SettingsAction("Find on Wi-Fi", "search", !sync.busy) {
                    g.task { g.message.value = g.sync.rediscover(peer) }
                }
                SettingsAction("Unpair", "x", !sync.busy, danger = true) { unpair = peer }
            }
        }
    }
    if (conflicts.isNotEmpty()) {
        for ((peerId, group) in songGroups) {
            if (group.size > 1) {
                val peerName = peers.firstOrNull { it.id == peerId }?.name ?: "Desktop"
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${group.size} pending song changes · $peerName", fontSize = 14.sp)
                        Hint("Review each song below, or resolve all ${group.size} song changes at once.")
                        FlowRow {
                            Action("Keep desktop for all (${group.size})", enabled = !sync.busy) {
                                val snapshot = group.toList()
                                g.task {
                                    val result = g.sync.resolveMany(snapshot, false)
                                    g.message.value = result.message
                                }
                            }
                            Action("Keep phone for all (${group.size})", enabled = !sync.busy) {
                                val snapshot = group.toList()
                                g.task {
                                    val result = g.sync.resolveMany(snapshot, true)
                                    g.message.value = result.message
                                }
                            }
                        }
                    }
                }
            }
        }
        val first = visibleConflictPage * 20
        val end = minOf(first + 20, conflicts.size)
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(conflictAnchor)) {
            Hint("Showing ${first + 1}–$end of ${conflicts.size} pending changes")
            if (lastConflictPage > 0) Row {
                Action("Previous", enabled = visibleConflictPage > 0) { turnConflictPage(visibleConflictPage - 1) }
                Action("Next", enabled = visibleConflictPage < lastConflictPage) { turnConflictPage(visibleConflictPage + 1) }
            }
        }
        for (conflict in conflicts.subList(first, end)) key(conflict.id) {
            ConflictView(g, conflict, songGroups[conflict.peer].orEmpty())
        }
        if (lastConflictPage > 0) Row {
            Action("Previous", enabled = visibleConflictPage > 0) { turnConflictPage(visibleConflictPage - 1) }
            Action("Next", enabled = visibleConflictPage < lastConflictPage) { turnConflictPage(visibleConflictPage + 1) }
        }
    }
    choosing?.let { peer -> SyncScopeDialog(g, peer, pickers) { choosing = null } }
    unpair?.let { peer ->
        Confirmation(
            "Unpair ${peer.name}?",
            "Local music and playlists are kept. Revoke this phone from desktop Settings too if you want its desktop credentials removed.",
            "Unpair",
            { unpair = null },
        ) {
            g.task { g.sync.unpair(peer) }
        }
    }
}

@Composable
private fun SyncScopeDialog(g: Graph, peer: Peer, pickers: Pickers, onDismiss: () -> Unit) {
    val tracks by g.library.tracks.collectAsStateWithLifecycle()
    val collections by g.library.collections.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf(peer.allMedia) }
    var selected by remember {
        mutableStateOf(
            runCatching {
                    JsonCodec.parseToJsonElement(peer.selected)
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet()
                }
                .getOrDefault(emptySet())
        )
    }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music to synchronize") },
        text = {
            Column {
                Choice("Entire library", all) { all = it }
                Hint(
                    "Library information and edits sync both ways. Audio is copied for the selection below."
                )
                Input(
                    query,
                    { query = it },
                    "Find songs or playlists",
                    "".let { Modifier },
                    "search",
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (!all) {
                        items(
                            collections.filter { query.isBlank() || it.name.contains(query, true) },
                            key = { "collection:" + it.id },
                        ) { c ->
                            val key = "collection:" + c.id
                            Choice(c.name, key in selected) {
                                selected = if (it) selected + key else selected - key
                            }
                        }
                        items(
                            tracks.filter {
                                query.isBlank() ||
                                    it.title.contains(query, true) ||
                                    it.artist.contains(query, true)
                            },
                            key = { "track:" + it.id },
                        ) { t ->
                            val key = "track:" + t.id
                            Choice(t.title + " · " + t.artist, key in selected) {
                                selected = if (it) selected + key else selected - key
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Action("Save and sync", enabled = all || selected.isNotEmpty()) {
                g.task {
                    g.sync.scope(peer, all, selected)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onDismiss()
                        if (g.prefs.state.value.musicDir.isBlank())
                            pickers.folder("sync:" + peer.id)
                        else g.sync.start(peer)
                    }
                }
            }
        },
        dismissButton = { Action("Cancel") { onDismiss() } },
    )
}

@Composable
fun SyncReviewPrompt(g: Graph) {
    val conflicts by g.sync.conflicts.collectAsStateWithLifecycle()
    val sync by g.sync.state.collectAsStateWithLifecycle()
    val deferred = remember { mutableStateListOf<String>() }
    val songGroups = remember(conflicts) { conflicts.filter { it.entity == "tracks" }.groupBy { it.peer } }
    val conflict = conflicts.firstOrNull { it.id !in deferred }
    if (sync.busy && sync.stage.startsWith("Applying song choices")) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Applying library choices") },
            text = { Progress(sync.stage, sync.progress) },
            confirmButton = { Action("Cancel", "x") { g.sync.cancel() } },
        )
    }
    if (conflict != null && !sync.busy) AlertDialog(
        onDismissRequest = { deferred.add(conflict.id) },
        title = {
            Text(
                if (conflicts.size > 1) "Choose a library version (${conflicts.size} remaining)"
                else "Choose a library version"
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Hint("This choice is shared with the connected desktop. Review later keeps the song unchanged until you choose in Settings → Phone sync.")
                ConflictView(g, conflict, songGroups[conflict.peer].orEmpty())
            }
        },
        confirmButton = { TextButton(onClick = { deferred.add(conflict.id) }) { Text("Review later") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictView(g: Graph, conflict: SyncConflict, sameDesktopSongs: List<SyncConflict>) {
    var bulk by remember(conflict.id, conflict.revision) { mutableStateOf<List<SyncConflict>?>(null) }
    var applying by remember(conflict.id, conflict.revision) { mutableStateOf(false) }
    fun choose(usePhone: Boolean) {
        if (applying) return
        applying = true
        val snapshot = bulk
        bulk = null
        g.task {
            try {
                if (snapshot != null) {
                    val result = g.sync.resolveMany(snapshot, usePhone)
                    g.message.value = result.message
                } else g.sync.resolve(conflict, usePhone)
            } finally {
                applying = false
            }
        }
    }
    val sync by g.sync.state.collectAsStateWithLifecycle()
    val tracks by g.library.tracks.collectAsStateWithLifecycle()
    val members by g.library.members.collectAsStateWithLifecycle()
    val collections by g.library.collections.collectAsStateWithLifecycle()
    val title =
        tracks.firstOrNull { it.id == conflict.key }?.title
            ?: collections.firstOrNull { it.id == conflict.key }?.name
            ?: runCatching { JsonCodec.parseToJsonElement(conflict.remote ?: conflict.local ?: "null").jsonObject["title"]?.jsonPrimitive?.content }.getOrNull()
            ?: "Library change"
    val desktop =
        runCatching { conflict.local?.let { JsonCodec.parseToJsonElement(it) } }.getOrNull()
    val phone =
        runCatching { conflict.remote?.let { JsonCodec.parseToJsonElement(it) } }.getOrNull()
    val fields =
        runCatching {
                JsonCodec.parseToJsonElement(conflict.fields).jsonArray.map {
                    it.jsonPrimitive.content
                }
            }
            .getOrDefault(emptyList())
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Review: $title", fontSize = 15.sp)
        for (field in fields) {
            val label =
                mapOf(
                    "volumePercent" to "Song volume",
                    "audioHash" to "Audio file",
                    "artHash" to "Artwork",
                    "position" to "Playlist order",
                    "entries" to "Playlist order",
                    "deletion" to "Removal",
                )[field] ?: field.replaceFirstChar { it.uppercase() }
            fun display(value: JsonElement?): String {
                if (value == null || value == JsonNull) return "Removed"
                if (field == "entries")
                    return value.jsonObject[field]?.jsonArray?.joinToString(" → ") { id ->
                        tracks
                            .firstOrNull {
                                it.id ==
                                    members
                                        .firstOrNull { m -> m.id == id.jsonPrimitive.content }
                                        ?.track
                            }
                            ?.title ?: "Removed song"
                    } ?: "Unchanged"
                if (field in listOf("audioHash", "artHash")) return "Changed"
                return value.jsonObject[field]?.toString()?.trim('"') ?: "Unchanged"
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "$label · Desktop",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(display(desktop), fontSize = 12.sp)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "$label · Phone",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(display(phone), fontSize = 12.sp)
                    }
                }
            }
        }
        fun effect(value: JsonElement?, name: String): String =
            if (value == null || value == JsonNull)
                "$name: remove this entry from both libraries. Existing audio files are kept on disk."
            else "$name: use this version on both devices. Metadata updates; changed audio/artwork transfers after the choice."
        Hint(effect(desktop, "Keep desktop"))
        Hint(effect(phone, "Keep phone"))
        if (conflict.entity == "tracks" && (sameDesktopSongs.size > 1 || bulk != null)) {
            Choice("Apply this choice to all ${bulk?.size ?: sameDesktopSongs.size} current song conflicts", bulk != null) { checked ->
                bulk = if (checked) sameDesktopSongs.toList() else null
            }
            if (bulk != null) Hint("Applies only to these ${bulk!!.size} songs on this desktop. New or changed conflicts remain for review.")
        }
        FlowRow {
            val count = bulk?.size?.let { " ($it songs)" }.orEmpty()
            Action("Keep desktop$count", enabled = !sync.busy && !applying) { choose(false) }
            Action("Keep phone$count", enabled = !sync.busy && !applying) { choose(true) }
        }
    }
}

@Composable
fun StatisticsScreen(g: Graph, onPlay: (Track, List<Track>) -> Unit) {
    val tracks by g.library.tracks.collectAsStateWithLifecycle()
    val counters by g.dao.observeCounters().collectAsStateWithLifecycle(emptyList())
    val plays by g.dao.observePlays().collectAsStateWithLifecycle(emptyList())
    var period by remember { mutableStateOf("week") }
    var artists by remember { mutableStateOf(false) }
    var insightsOpen by remember { mutableStateOf(false) }
    var exact by remember { mutableStateOf(false) }
    val milliseconds = remember(counters) { counters.sumOf { it.milliseconds } }
    val minutes = milliseconds / 60000
    val compact =
        if (minutes >= 1440) "${minutes/1440}d${minutes/60%24}h" else "${minutes/60}h${minutes%60}m"
    val insights =
        remember(period, plays, counters, tracks) {
            computeListeningInsights(period, plays, counters, tracks)
        }
    val counts =
        remember(counters, plays, period) {
            if (period == "all")
                counters.groupBy { it.track }.mapValues { it.value.sumOf { c -> c.plays } }
            else {
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val cutoff =
                    when (period) {
                        "day" -> cal.timeInMillis
                        "week" -> cal.timeInMillis - 6L * 86400000L
                        else -> cal.timeInMillis - 29L * 86400000L
                    }
                plays
                    .filter { it.playedAt >= cutoff }
                    .groupingBy { it.track }
                    .eachCount()
                    .mapValues { it.value.toLong() }
            }
        }
    val ordered =
        remember(tracks, counts) {
            tracks.filter { (counts[it.id] ?: 0) > 0 }.sortedByDescending { counts[it.id] ?: 0 }
        }
    val artistGroups =
        remember(ordered, counts) {
            val buckets = LinkedHashMap<String, Pair<String, MutableList<Track>>>()
            for (track in ordered) {
                for (artistName in splitArtists(track.artist)) {
                    val key = artistName.lowercase(Locale.ROOT)
                    val entry = buckets.getOrPut(key) { artistName to mutableListOf() }
                    entry.second += track
                }
            }
            buckets.entries
                .map { (key, entry) -> Triple(key, entry.first, entry.second.toList()) }
                .sortedByDescending { (_, _, group) -> group.sumOf { counts[it.id] ?: 0 } }
        }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Section("Me!")
            Text(
                compact + " listened",
                Modifier.clickable { exact = true }.padding(vertical = 8.dp),
                fontSize = 28.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PickMenu(
                    period,
                    listOf("day", "week", "month", "all"),
                    label = {
                        when (it) {
                            "day" -> "Today"
                            "week" -> "Week"
                            "month" -> "Month"
                            else -> "All time"
                        }
                    },
                ) {
                    period = it
                }
                PickMenu(if (artists) "Artists" else "Songs", listOf("Songs", "Artists")) {
                    artists = it == "Artists"
                }
                Spacer(Modifier.weight(1f))
                Action(
                    if (insightsOpen) "Hide insights" else "Insights",
                    if (insightsOpen) "chevron-up" else "chevron-down",
                ) {
                    insightsOpen = !insightsOpen
                }
            }
            if (insightsOpen) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InsightCard(
                    label = "Most heard artist",
                    value = insights.topArtistName,
                    sub =
                        if (insights.topArtistPlays > 0)
                            "${insights.topArtistPlays} " + if (insights.topArtistPlays == 1L) "play" else "plays"
                        else "No plays",
                    modifier = Modifier.weight(1f),
                )
                InsightCard(
                    label = "Plays & activity",
                    value = "${insights.totalPlays} " + if (insights.totalPlays == 1L) "play" else "plays",
                    sub =
                        if (period == "day") "${insights.uniqueArtists} artists · ${insights.streakDays}d streak"
                        else "${insights.activeDays} active days · ${insights.streakDays}d streak",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InsightCard(
                    label = "Top song",
                    value = insights.topTrackTitle,
                    sub =
                        if (insights.topTrackPlays > 0)
                            "${insights.topTrackPlays} " + if (insights.topTrackPlays == 1L) "play" else "plays"
                        else "No plays",
                    modifier = Modifier.weight(1f),
                )
                InsightCard(
                    label = "Peak listening",
                    value = insights.peakSummary,
                    sub = "${insights.uniqueSongs} unique songs",
                    modifier = Modifier.weight(1f),
                )
            }
            if (insights.buckets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                insights.chartTitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (insights.maxBucket > 0)
                                    "Peak: ${insights.maxBucket} " + if (insights.maxBucket == 1) "play" else "plays"
                                else "No plays",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().height(76.dp),
                            horizontalArrangement =
                                Arrangement.spacedBy(if (insights.buckets.size > 10) 2.dp else 6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            for (bucket in insights.buckets) {
                                val fraction =
                                    if (insights.maxBucket > 0)
                                        bucket.count.toFloat() / insights.maxBucket.toFloat()
                                    else 0f
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom,
                                ) {
                                    Box(
                                        Modifier.weight(1f).fillMaxWidth(),
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth(
                                                    if (insights.buckets.size > 10) 0.85f else 0.55f
                                                )
                                                .fillMaxHeight(
                                                    if (bucket.count > 0) fraction.coerceAtLeast(0.08f)
                                                    else 0.04f
                                                )
                                                .background(
                                                    if (bucket.count > 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.outlineVariant,
                                                    MaterialTheme.shapes.extraSmall,
                                                )
                                        )
                                    }
                                    if (insights.buckets.size <= 10 || bucket.label.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            bucket.label,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (artists) {
            items(artistGroups, key = { it.first }) { (_, artistName, group) ->
                val count = group.sumOf { counts[it.id] ?: 0 }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onPlay(group.first(), group) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(group.firstOrNull { it.art.isNotBlank() }?.art ?: "")
                    Spacer(Modifier.width(12.dp))
                    Text(artistName, Modifier.weight(1f))
                    Hint(if (count == 1L) "1 play" else "$count plays")
                }
            }
        } else {
            items(ordered, key = { it.id }) { track ->
                val count = counts[track.id] ?: 0L
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onPlay(track, ordered) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track.art)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp,
                        )
                        Hint(track.artist)
                    }
                    Hint(if (count == 1L) "1 play" else "$count plays")
                }
            }
        }
        if (ordered.isEmpty())
            item { Hint("Play counts and listening time appear as you listen.") }
    }
    if (exact)
        AlertDialog(
            onDismissRequest = { exact = false },
            text = { Text("${minutes/60}h${minutes%60}m") },
            confirmButton = { Action("Close") { exact = false } },
        )
}

@Composable
private fun InsightCard(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sub,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LicensesDialog(g: Graph, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf("NOTICE.md") }
    val names = remember { g.context.assets.list("licenses").orEmpty().sorted() }
    val text by
        produceState("", selected) {
            value =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    g.context.assets.open("licenses/$selected").bufferedReader().use {
                        it.readText()
                    }
                }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Third-party software") },
        text = {
            Column {
                PickMenu(selected, names) { selected = it }
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { Action("Close") { onDismiss() } },
    )
}

@Composable
private fun DeveloperSettings(g: Graph) {
    val resetting by g.resetting.collectAsStateWithLifecycle()
    val sync by g.sync.state.collectAsStateWithLifecycle()
    val backup by g.backup.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var removeFiles by remember { mutableStateOf(false) }
    Section("Developer tools")
    Hint("Reset only this phone's library. Desktop music and media files are kept.")
    SettingsAction("Reset phone library", "trash-2", !resetting && !sync.busy && !backup.busy, danger = true) {
        text = ""
        removeFiles = false
        confirming = true
    }
    if (confirming)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset phone library?") },
            text = {
                Column {
                    Text(
                        "Removes songs, playlists, likes, listening and download history, watched folders and the playback queue from this phone. Original files in user folders and recoverable Trash are kept. Desktop music is unchanged. Pairing is kept with its sync cursor reset, so Sync can import the library again."
                    )
                    Choice("Also delete app-private synced audio and artwork", removeFiles) {
                        removeFiles = it
                    }
                    Input(text, { text = it }, "Type RESET")
                }
            },
            confirmButton = {
                Action("Reset phone library", "trash-2", text == "RESET") {
                    confirming = false
                    g.task { g.developer.reset(removeFiles) }
                }
            },
            dismissButton = { Action("Cancel") { confirming = false } },
        )
    if (resetting)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Resetting phone library") },
            text = { Progress("Clearing local records") },
            confirmButton = {},
        )
}
