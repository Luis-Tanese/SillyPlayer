package dev.sillyplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sillyplayer.Graph
import dev.sillyplayer.R
import dev.sillyplayer.data.*
import dev.sillyplayer.media.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

data class Pickers(
    val folder: (String) -> Unit,
    val files: () -> Unit,
    val image: ((String) -> Unit) -> Unit,
    val backup: () -> Unit,
    val restore: () -> Unit,
    val locate: (String) -> Unit,
    val exportPlaylist: (String, String) -> Unit = { _, _ -> },
    val importPlaylist: () -> Unit = {},
)

@Composable
fun AppScreen(g: Graph, pickers: Pickers) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.IO) { g.sync.watchReviews() }
        }
    }
    val prefs by g.prefs.state.collectAsStateWithLifecycle()
    val engine by g.engine.collectAsStateWithLifecycle()
    val fallback = remember { MutableStateFlow(PlaybackState()) }
    val playback by (engine?.state ?: fallback).collectAsStateWithLifecycle()
    val tracks by g.library.tracks.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Music") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<Pair<Track, String>?>(null) }
    val holder = rememberSaveableStateHolder()
    val notice by g.message.collectAsStateWithLifecycle()
    val pairing by g.sync.pendingLink.collectAsStateWithLifecycle()
    val openPlayer by g.openPlayer.collectAsStateWithLifecycle()
    val update by g.updates.state.collectAsStateWithLifecycle()
    var updateBannerDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openPlayer, playback.music) {
        if (openPlayer) {
            page = "Music"
            expanded = playback.music != null
            if (playback.music != null) g.openPlayer.value = false
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var split by rememberSaveable { mutableFloatStateOf(.5f) }
    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbar.showSnackbar(notice)
            g.message.value = ""
        }
    }
    LaunchedEffect(pairing) {
        if (pairing.isNotBlank()) {
            expanded = false
            page = "Settings"
        }
    }
    LaunchedEffect(page, expanded) {
        if (page !in listOf("Browse", "Split") || expanded) {
            g.online.cancelPlayback()
            engine?.leaveBrowse()
        }
    }
    BackHandler(expanded || queue || page == "Settings") {
        when {
            queue -> queue = false
            expanded -> expanded = false
            else -> page = "Music"
        }
    }
    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val current = trackById[playback.music?.id] ?: playback.music
    SillyTheme(prefs) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                val splitWidth = with(LocalDensity.current) { maxWidth.toPx() }
                LaunchedEffect(wide) { if (!wide && page == "Split") page = "Music" }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    painterResource(R.drawable.sillyplayer),
                                    "SillyPlayer",
                                    Modifier.size(34.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (expanded) "Music"
                                    else if (page == "Settings") "Settings" else "SillyPlayer",
                                    Modifier.weight(1f),
                                    fontSize = 17.sp,
                                )
                                if (wide)
                                    TextButton({
                                        expanded = false
                                        page = "Split"
                                    }) {
                                        Text(
                                            "Split",
                                            color =
                                                if (page == "Split") MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                IconAction("settings", "Settings") {
                                    expanded = false
                                    page = "Settings"
                                }
                            }
                            if (update.updateAvailable && prefs.updateNotifications && !updateBannerDismissed && !expanded) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "SillyPlayer v${update.latestVersion} is available",
                                        Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Action("View", "external-link") { g.updates.openReleasePage() }
                                    IconAction("x", "Dismiss") { updateBannerDismissed = true }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        Column {
                            if (!expanded)
                                CompactPlayer(
                                    g,
                                    playback,
                                    current,
                                    onExpand = {
                                        page = "Music"
                                        expanded = true
                                    },
                                    onQueue = { queue = true },
                                )
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .navigationBarsPadding()
                            ) {
                                for ((name, icon) in
                                    listOf(
                                        "Music" to "music-2",
                                        "Browse" to "search",
                                        "Downloads" to "download",
                                        "Me!" to "heart",
                                    )) {
                                    Column(
                                        Modifier.weight(1f)
                                            .clickable {
                                                page = name
                                                expanded = false
                                            }
                                            .padding(top = 7.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Glyph(icon, modifier = Modifier.size(20.dp))
                                        Text(
                                            name,
                                            fontSize = 11.sp,
                                            color =
                                                if (page == name) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        Box(
                                            Modifier.fillMaxWidth(.5f)
                                                .height(2.dp)
                                                .background(
                                                    if (page == name)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        androidx.compose.ui.graphics.Color
                                                            .Transparent
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (expanded)
                            ExpandedPlayer(
                                g,
                                playback,
                                current,
                                onBack = { expanded = false },
                                onQueue = { queue = true },
                                onAction = { t, a -> action = t to a },
                            )
                        else
                            holder.SaveableStateProvider(page) {
                                when (page) {
                                    "Music" ->
                                        MusicScreen(
                                            g,
                                            pickers,
                                            onExpand = { expanded = true },
                                            onAction = { t, a -> action = t to a },
                                        )
                                    "Browse" -> BrowseScreen(g, pickers)
                                    "Downloads" -> DownloadsScreen(g)
                                    "Me!" ->
                                        StatisticsScreen(
                                            g,
                                            onPlay = { t, list ->
                                                engine?.playTrack(t, list, "Most played")
                                            },
                                        )
                                    "Settings" -> SettingsScreen(g, pickers)
                                    "Split" ->
                                        Row(Modifier.fillMaxSize()) {
                                            Box(Modifier.weight(split)) {
                                                MusicScreen(
                                                    g,
                                                    pickers,
                                                    onExpand = {
                                                        page = "Music"
                                                        expanded = true
                                                    },
                                                    onAction = { t, a -> action = t to a },
                                                )
                                            }
                                            Box(
                                                Modifier.fillMaxHeight()
                                                    .width(8.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    )
                                                    .pointerInput(splitWidth) {
                                                        detectHorizontalDragGestures {
                                                            change,
                                                            amount ->
                                                            change.consume()
                                                            split =
                                                                (split +
                                                                        amount /
                                                                            splitWidth
                                                                                .coerceAtLeast(1f))
                                                                    .coerceIn(.3f, .7f)
                                                        }
                                                    }
                                            )
                                            Box(Modifier.weight(1 - split)) {
                                                BrowseScreen(g, pickers)
                                            }
                                        }
                                }
                            }
                    }
                }
                SyncReviewPrompt(g)
                if (queue) QueuePanel(g, playback) { queue = false }
                action?.let { (track, name) ->
                    SongActionDialog(
                        g,
                        pickers,
                        trackById[track.id] ?: track,
                        name,
                    ) {
                        action = null
                    }
                }
            }
        }
    }
}
