package dev.sillyplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.widget.RemoteViews
import dev.sillyplayer.MainActivity
import dev.sillyplayer.R
import dev.sillyplayer.media.PlaybackService
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

data class WidgetState(
    val title: String = "SillyPlayer",
    val artist: String = "Select a song in the app",
    val art: String = "",
    val selected: Boolean = false,
    val playing: Boolean = false,
    val background: Int = 0xff1e1e2e.toInt(),
    val foreground: Int = 0xffcdd6f4.toInt(),
    val accent: Int = 0xffcba6f7.toInt(),
)

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        executor.execute {
            try {
                render(context, load(context), ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: android.os.Bundle,
    ) {
        onUpdate(context, manager, intArrayOf(id))
    }

    companion object {
        const val TOGGLE = "dev.sillyplayer.widget.TOGGLE"
        const val PREVIOUS = "dev.sillyplayer.widget.PREVIOUS"
        const val NEXT = "dev.sillyplayer.widget.NEXT"
        const val OPEN_PLAYER = "dev.sillyplayer.OPEN_PLAYER"
        private val executor = Executors.newSingleThreadExecutor()

        fun publish(context: Context, state: WidgetState) {
            context
                .getSharedPreferences("player-widget", Context.MODE_PRIVATE)
                .edit()
                .putString("title", state.title)
                .putString("artist", state.artist)
                .putString("art", state.art)
                .putBoolean("selected", state.selected)
                .putBoolean("playing", state.playing)
                .putInt("pid", Process.myPid())
                .putInt("background", state.background)
                .putInt("foreground", state.foreground)
                .putInt("accent", state.accent)
                .apply()
            val ids =
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isNotEmpty()) executor.execute { render(context, state, ids) }
        }

        fun load(context: Context): WidgetState {
            val p = context.getSharedPreferences("player-widget", Context.MODE_PRIVATE)
            return WidgetState(
                p.getString("title", "SillyPlayer")!!,
                p.getString("artist", "Select a song in the app")!!,
                p.getString("art", "")!!,
                p.getBoolean("selected", false),
                p.getBoolean("playing", false) && p.getInt("pid", -1) == Process.myPid(),
                p.getInt("background", 0xff1e1e2e.toInt()),
                p.getInt("foreground", 0xffcdd6f4.toInt()),
                p.getInt("accent", 0xffcba6f7.toInt()),
            )
        }

        private fun open(context: Context) =
            PendingIntent.getActivity(
                context,
                8200,
                Intent(context, MainActivity::class.java)
                    .setAction(OPEN_PLAYER)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun command(context: Context, action: String, id: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                id,
                Intent(context, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun artwork(context: Context, value: String): Bitmap? =
            runCatching {
                    if (value.isBlank()) return null
                    val uri = Uri.parse(value)
                    fun input(): InputStream? =
                        when (uri.scheme) {
                            "file" -> FileInputStream(uri.path!!)
                            "content" -> context.contentResolver.openInputStream(uri)
                            else -> null
                        }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val source = input() ?: return null
                    source.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (
                                maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 160
                            ) inSampleSize *= 2
                        }
                    input()?.use { BitmapFactory.decodeStream(it, null, options) }
                }
                .getOrNull()

        private fun render(context: Context, state: WidgetState, ids: IntArray) {
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.player_widget)
            views.setInt(R.id.widget_background, "setColorFilter", state.background)
            views.setTextViewText(R.id.widget_title, state.title)
            views.setTextViewText(R.id.widget_artist, state.artist)
            views.setTextColor(R.id.widget_title, state.foreground)
            views.setTextColor(R.id.widget_artist, state.foreground)
            views.setImageViewResource(
                R.id.widget_play,
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
            )
            for (id in listOf(R.id.widget_previous, R.id.widget_play, R.id.widget_next)) {
                views.setInt(id, "setColorFilter", state.accent)
                views.setBoolean(id, "setEnabled", state.selected)
            }
            val image = artwork(context, state.art)
            if (image != null) views.setImageViewBitmap(R.id.widget_art, image)
            else views.setImageViewResource(R.id.widget_art, R.drawable.widget_music_placeholder)
            views.setContentDescription(R.id.widget_play, if (state.playing) "Pause" else "Play")
            views.setOnClickPendingIntent(R.id.widget_info, open(context))
            views.setOnClickPendingIntent(R.id.widget_art, open(context))
            views.setOnClickPendingIntent(R.id.widget_previous, command(context, PREVIOUS, 8201))
            views.setOnClickPendingIntent(R.id.widget_play, command(context, TOGGLE, 8202))
            views.setOnClickPendingIntent(R.id.widget_next, command(context, NEXT, 8203))
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }
    }
}
