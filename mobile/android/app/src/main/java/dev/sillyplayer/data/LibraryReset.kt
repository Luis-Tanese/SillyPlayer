package dev.sillyplayer.data

import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import dev.sillyplayer.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LibraryReset(private val g: Graph) {
    suspend fun reset(removePrivateMedia: Boolean = false) {
        g.prefs.loaded.await()
        g.library.ready.await()
        check(g.prefs.state.value.developerMode) { "Enable Developer mode first" }
        check(!g.sync.state.value.busy && !g.backup.state.value.busy && !g.library.isIndexing) {
            "Finish or cancel sync, indexing and backup before resetting"
        }
        val pending =
            WorkManager.getInstance(g.context)
                .getWorkInfos(
                    WorkQuery.Builder.fromStates(
                            listOf(
                                WorkInfo.State.RUNNING,
                                WorkInfo.State.ENQUEUED,
                                WorkInfo.State.BLOCKED,
                            )
                        )
                        .build()
                )
                .get()
        check(pending.isEmpty()) { "Finish or cancel background jobs before resetting" }
        check(g.resetting.compareAndSet(false, true)) { "A reset is already running" }
        try {
            withTimeout(30000) {
                g.downloadMutex.withLock {
                    g.syncGate.withLock {
                        g.fileGate.withLock {
                            withContext(Dispatchers.Main) {
                                g.online.closePlayer()
                                g.engine.value?.resetLibrary()
                            }
                            g.db.withTransaction {
                                for (sql in LibraryResetStatements) g.db.openHelper.writableDatabase
                                    .execSQL(sql)
                            }
                            g.prefs.update { it.copy(queue = QueueState(), sleepEnd = 0) }
                            if (removePrivateMedia) {
                                val references =
                                    g.dao.trash().flatMap { listOf(it.original, it.stored) } +
                                        g.dao.allTracks().flatMap { listOf(it.uri, it.art) }
                                val protected =
                                    references
                                        .mapNotNull {
                                            runCatching { java.io.File(java.net.URI(it)) }
                                                .getOrNull()
                                        }
                                        .toSet()
                                clearPrivateSyncMedia(g.context.filesDir, protected)
                            }
                        }
                    }
                }
            }
            g.message.value =
                "Phone library reset. Original files and Trash kept; desktop unchanged. Choose music and Sync to import again."
        } finally {
            g.resetting.value = false
        }
    }
}
