package dev.sillyplayer.data

import android.content.Context
import androidx.work.*
import dev.sillyplayer.graph
import dev.sillyplayer.online.workNotification
import kotlinx.coroutines.CancellationException

class BackupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val uri = inputData.getString("uri") ?: return Result.failure()
        val restoring = inputData.getBoolean("restore", false)
        setForeground(
            workNotification(
                applicationContext,
                43818,
                "SillyPlayer library",
                if (restoring) "Verifying library backup" else "Backing up library",
            )
        )
        return try {
            g.library.ready.await()
            if (restoring) g.backup.restore(uri) else g.backup.export(uri)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
