package dev.sillyplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.sillyplayer.media.PlaybackService
import dev.sillyplayer.ui.AppScreen
import dev.sillyplayer.ui.Pickers

class MainActivity : ComponentActivity() {
    private var controllerFuture:
        com.google.common.util.concurrent.ListenableFuture<MediaController>? =
        null
    private var folderPurpose = "root"
    private var locateId = ""
    private var exportCollectionId = ""
    private var imageCallback: ((String) -> Unit)? = null
    private val folder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val purpose = folderPurpose
                graph.task {
                    graph.library.grant(uri)
                    if (purpose == "destination" || purpose.startsWith("sync:")) {
                        graph.library.chooseMusicFolder(uri)
                        if (purpose.startsWith("sync:")) {
                            val peer =
                                graph.dao.peers().firstOrNull {
                                    it.id == purpose.removePrefix("sync:")
                                }
                            peer?.let { graph.sync.start(it) }
                        }
                    } else if (purpose == "reinstate") {
                        graph.message.value = graph.library.reinstateFolder(uri)
                    } else {
                        graph.library.register(uri)
                        if (purpose.startsWith("replace:"))
                            graph.library.removeRoot(purpose.removePrefix("replace:"))
                    }
                }
            }
        }
    private val files =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            graph.task {
                for (uri in uris) {
                    graph.library.grant(uri)
                    graph.library.index(uri.toString())
                }
            }
        }
    private val locate =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                graph.task {
                    graph.library.grant(uri)
                    graph.library.locate(locateId, uri.toString())
                }
        }
    private val image =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                graph.library.grant(uri)
                imageCallback?.invoke(uri.toString())
            }
            imageCallback = null
        }
    private val backup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri
            ->
            if (uri != null) {
                graph.library.grant(uri)
                graph.backup.start(uri.toString(), false)
            }
        }
    private val restore =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                graph.library.grant(uri)
                graph.backup.start(uri.toString(), true)
            }
        }
    private val exportM3u =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            val cid = exportCollectionId
            if (uri != null && cid.isNotBlank()) {
                graph.task {
                    val msg = graph.library.exportPlaylistM3u(cid, uri.toString())
                    graph.message.value = msg
                }
            }
        }
    private val importM3u =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                graph.task {
                    val msg = graph.library.importPlaylistM3u(uri.toString())
                    graph.message.value = msg
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPurpose = savedInstanceState?.getString("folderPurpose") ?: "root"
        locateId = savedInstanceState?.getString("locateId") ?: ""
        exportCollectionId = savedInstanceState?.getString("exportCollectionId") ?: ""
        enableEdgeToEdge()
        handle(intent)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (!isDestroyed)
                    runCatching { future.get() }
                        .onFailure {
                            graph.message.value = "Playback service could not start: ${it.message}"
                        }
            },
            ContextCompat.getMainExecutor(this),
        )
        setContent {
            AppScreen(
                graph,
                Pickers(
                    folder = { purpose ->
                        folderPurpose = purpose
                        folder.launch(
                            if (purpose == "destination" || purpose.startsWith("sync:"))
                                android.net.Uri.parse(
                                    "content://com.android.externalstorage.documents/document/primary%3AMusic"
                                )
                            else null
                        )
                    },
                    files = { files.launch(arrayOf("audio/*", "application/ogg")) },
                    image = { callback ->
                        imageCallback = callback
                        image.launch(arrayOf("image/*"))
                    },
                    backup = { backup.launch("SillyPlayer-library.zip") },
                    restore = {
                        restore.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    locate = { id ->
                        locateId = id
                        locate.launch(arrayOf("audio/*"))
                    },
                    exportPlaylist = { cid, suggestedName ->
                        exportCollectionId = cid
                        val safe =
                            suggestedName.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim()
                                .ifEmpty { "Playlist" }
                        exportM3u.launch("$safe.m3u8")
                    },
                    importPlaylist = {
                        importM3u.launch(
                            arrayOf(
                                "audio/x-mpegurl",
                                "audio/mpegurl",
                                "application/vnd.apple.mpegurl",
                                "text/plain",
                                "*/*",
                            )
                        )
                    },
                ),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folderPurpose", folderPurpose)
        outState.putString("locateId", locateId)
        outState.putString("exportCollectionId", exportCollectionId)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == dev.sillyplayer.widget.PlayerWidget.OPEN_PLAYER)
            graph.openPlayer.value = true
        val link = intent?.dataString ?: intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (link.startsWith("sillyplayer://pair")) graph.sync.pendingLink.value = link
        else if (link.startsWith("http")) graph.online.search("youtube", link)
    }

    override fun onStart() {
        super.onStart()
        graph.foreground.value = true
    }

    override fun onStop() {
        graph.foreground.value = false
        graph.engine.value?.checkpoint()
        graph.library.recovery.flushNow()
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
