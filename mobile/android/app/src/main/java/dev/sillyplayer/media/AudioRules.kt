package dev.sillyplayer.media

/** combined signal percentage; saved sliders remain independent and can each reach 300% */
fun effectiveVolume(master: Float, song: Float): Float {
    val main = master.takeIf { it.isFinite() }?.coerceIn(0f, 300f) ?: 100f
    val modifier = song.takeIf { it.isFinite() }?.coerceIn(0f, 300f) ?: 100f
    return (main * modifier / 100f).coerceAtMost(300f)
}

fun songOutputGain(master: Float, song: Float): Float = effectiveVolume(master, song) / 100f

fun pitchEffectLabel(speed: Float): String = if (speed < 1f) "Daycore" else "Nightcore"
