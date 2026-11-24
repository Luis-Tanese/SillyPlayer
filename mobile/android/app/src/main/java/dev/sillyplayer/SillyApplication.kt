package dev.sillyplayer

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.sillyplayer.data.*
import dev.sillyplayer.media.PlaybackEngine
import dev.sillyplayer.online.OnlineRepository
import dev.sillyplayer.sync.SyncClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class SillyApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

class Graph(val context: Context) {
    val fileGate = kotlinx.coroutines.sync.Mutex()
    val resetting = MutableStateFlow(false)
    val foreground = MutableStateFlow(false)
    val syncGate = kotlinx.coroutines.sync.Mutex()
    val downloadMutex = kotlinx.coroutines.sync.Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.sqlite3").build()
    val dao = db.dao()
    val prefs = PreferenceStore(context, scope)
    val message = MutableStateFlow("")
    val openPlayer = MutableStateFlow(false)
    val playbackError =
        MutableStateFlow(
            runCatching { File(context.filesDir, "last-playback-error.txt").readText().take(16000) }
                .getOrDefault("")
        )
    val library = LibraryRepository(this)
    val online = OnlineRepository(this)
    val updates = dev.sillyplayer.online.UpdateChecker(this)
    val backup = BackupRepository(this)
    val sync = SyncClient(this)
    val developer = LibraryReset(this)
    val engine = MutableStateFlow<PlaybackEngine?>(null)

    init {
        library.observeRecovery()
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("rhythm-analysis")
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("rhythm-analysis-v2")
    }

    fun task(block: suspend () -> Unit) {
        scope.launch {
            if (resetting.value) return@launch
            runCatching { block() }
                .onFailure {
                    message.value = it.message ?: "Operation failed"
                    android.util.Log.e("SillyPlayer", it.message, it)
                }
        }
    }

    fun cache(name: String): File = File(context.cacheDir, name).also { it.parentFile?.mkdirs() }
}

val Context.graph: Graph
    get() = (applicationContext as SillyApplication).graph
