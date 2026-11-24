package dev.sillyplayer.online

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.sillyplayer.BuildConfig
import dev.sillyplayer.Graph
import dev.sillyplayer.R
import dev.sillyplayer.data.JsonCodec
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateState(
    val checking: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String = "",
    val releaseUrl: String = "",
    val status: String = "",
)

internal fun parseSemver(tag: String): List<Int>? {
    val clean = tag.trim().removePrefix("v").removePrefix("V").substringBefore('-')
    if (clean.isEmpty()) return null
    val parts = clean.split('.')
    if (parts.isEmpty() || parts.size > 4) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

internal fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
    val latest = parseSemver(latestTag) ?: return false
    val current = parseSemver(currentVersion) ?: return false
    val len = maxOf(latest.size, current.size)
    for (i in 0 until len) {
        val a = latest.getOrElse(i) { 0 }
        val b = current.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

class UpdateChecker(private val g: Graph) {
    val state = MutableStateFlow(UpdateState())
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    @Volatile private var lastCheckAt = 0L

    init {
        g.scope.launch {
            g.prefs.loaded.await()
            delay(3000)
            while (isActive) {
                if (g.prefs.state.value.updateNotifications) {
                    check(manual = false)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        g.scope.launch {
            g.prefs.loaded.await()
            g.foreground.collect { visible ->
                if (!visible || !g.prefs.state.value.updateNotifications) return@collect
                if (lastCheckAt > 0L && SystemClock.elapsedRealtime() - lastCheckAt >= CHECK_INTERVAL_MS) {
                    check(manual = false)
                }
            }
        }
    }

    suspend fun check(manual: Boolean = false) {
        if (state.value.checking) return
        lastCheckAt = SystemClock.elapsedRealtime()
        state.value = state.value.copy(checking = true, status = if (manual) "Checking for updates…" else state.value.status)
        try {
            val request =
                Request.Builder()
                    .url(RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SillyPlayer/${BuildConfig.VERSION_NAME}")
                    .build()
            val body =
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
                        response.body?.string() ?: error("Empty response")
                    }
                }
            val obj = JsonCodec.parseToJsonElement(body).jsonObject
            if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true ||
                obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                state.value = UpdateState(status = "Up to date (v${BuildConfig.VERSION_NAME})")
                return
            }
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url =
                obj["html_url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://github.com/") }
                    ?: RELEASES_PAGE
            val cleanVersion = tag.removePrefix("v").removePrefix("V")
            if (isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                state.value =
                    UpdateState(
                        checking = false,
                        updateAvailable = true,
                        latestVersion = cleanVersion,
                        releaseUrl = url,
                        status = "Version $cleanVersion is available",
                    )
                val prefs = g.prefs.state.value
                if (!manual && prefs.updateNotifications && prefs.lastNotifiedVersion != cleanVersion) {
                    showNotification(cleanVersion, url)
                    g.prefs.update { it.copy(lastNotifiedVersion = cleanVersion) }
                }
            } else {
                state.value =
                    UpdateState(
                        checking = false,
                        updateAvailable = false,
                        latestVersion = cleanVersion,
                        releaseUrl = url,
                        status = if (manual) "You're on the latest version (v${BuildConfig.VERSION_NAME})" else "",
                    )
            }
        } catch (e: Exception) {
            state.value =
                state.value.copy(
                    checking = false,
                    status = if (manual) "Could not check for updates: ${e.message ?: "network error"}" else state.value.status,
                )
        }
    }

    fun openReleasePage(url: String = state.value.releaseUrl.ifBlank { RELEASES_PAGE }) {
        runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            g.context.startActivity(intent)
        }
    }

    private fun showNotification(version: String, url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(g.context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = g.context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openIntent =
            PendingIntent.getActivity(
                g.context,
                NOTIFICATION_ID,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(g.context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_2)
                .setContentTitle("SillyPlayer v$version is out")
                .setContentText("Tap to view the release page on GitHub")
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/Luis-Tanese/SillyPlayer/releases/latest"
        const val RELEASES_PAGE = "https://github.com/Luis-Tanese/SillyPlayer/releases"
        private const val CHANNEL_ID = "sillyplayer_updates"
        private const val NOTIFICATION_ID = 43820
        private const val CHECK_INTERVAL_MS = 2L * 60L * 60L * 1000L
    }
}
