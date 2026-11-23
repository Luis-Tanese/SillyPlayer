package dev.sillyplayer.media

import dev.sillyplayer.data.Play
import kotlin.math.min

/** paused playback needs no position polling; Media3 callbacks publish state changes */
internal fun playbackTickIntervalMs(playing: Boolean, visible: Boolean): Long? =
    if (!playing) null else if (visible) 250L else 15000L

/** use elapsed playback time, including a late background tick, without a one-second clamp */
internal fun advanceListening(session: Play, elapsedMs: Long, durationMs: Long): Play {
    val heard = session.heardMs + elapsedMs.coerceAtLeast(0)
    return session.copy(
        heardMs = heard,
        qualified = session.qualified || heard >= min(30000L, durationMs / 2),
    )
}
