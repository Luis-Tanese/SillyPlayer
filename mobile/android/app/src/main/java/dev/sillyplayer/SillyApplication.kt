package dev.sillyplayer

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.sillyplayer.data.*
import dev.sillyplayer.media.PlaybackEngine
import dev.sillyplayer.online.OnlineRepository
import dev.sillyplayer.sync.SyncClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class SillyApplication : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    // share the app OkHttp client with Coil so image loading doesnt spin up a duplicate pool
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.online.http }))
            }
            .build()
}

class Graph(val context: Context) {
    val fileGate = kotlinx.coroutines.sync.Mutex()
    val resetting = MutableStateFlow(false)
    val foreground = MutableStateFlow(false)
    val syncGate = kotlinx.coroutines.sync.Mutex()
    val downloadMutex = kotlinx.coroutines.sync.Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "library.sqlite3")
            .addMigrations(LibraryDatabase.MIGRATION_1_2)
            .build()
    val dao = db.dao()
    val prefs = PreferenceStore(context, scope)
    val message = MutableStateFlow("")
    val openPlayer = MutableStateFlow(false)
    val playbackError = MutableStateFlow("")
    val library = LibraryRepository(this)
    val online = OnlineRepository(this)
    val updates = dev.sillyplayer.online.UpdateChecker(this)
    val backup = BackupRepository(this)
    val sync = SyncClient(this)
    val developer = LibraryReset(this)
    val engine = MutableStateFlow<PlaybackEngine?>(null)

    init {
        library.observeRecovery()
        // keep cold-start disk I/O and one-time legacy WorkManager cleanup off the main thread
        scope.launch {
            val savedError =
                runCatching { File(context.filesDir, "last-playback-error.txt").readText().take(16000) }
                    .getOrDefault("")
            if (savedError.isNotEmpty() && playbackError.value.isEmpty()) {
                playbackError.value = savedError
            }
            val flags = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
            if (!flags.getBoolean("legacy_rhythm_cancelled", false)) {
                runCatching {
                    val wm = androidx.work.WorkManager.getInstance(context)
                    wm.cancelUniqueWork("rhythm-analysis")
                    wm.cancelUniqueWork("rhythm-analysis-v2")
                }
                flags.edit().putBoolean("legacy_rhythm_cancelled", true).apply()
            }
        }
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
