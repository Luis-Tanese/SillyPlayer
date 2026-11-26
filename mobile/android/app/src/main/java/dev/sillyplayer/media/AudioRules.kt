package dev.sillyplayer.media

/** combined signal percentage; saved sliders remain independent and can each reach 300% */
fun effectiveVolume(master: Float, song: Float): Float {
    val main = master.takeIf { it.isFinite() }?.coerceIn(0f, 300f) ?: 100f
    val modifier = song.takeIf { it.isFinite() }?.coerceIn(0f, 300f) ?: 100f
    return (main * modifier / 100f).coerceAtMost(300f)
}

fun songOutputGain(master: Float, song: Float): Float = effectiveVolume(master, song) / 100f

// exoplayer player.volume is linear amplitude while human hearing is logarithmic, so square the ramp so fades dont snap loud right away
fun perceptualFadeGain(progress: Float): Float {
    val p = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    return p * p
}

fun pitchEffectLabel(speed: Float): String = if (speed < 1f) "Daycore" else "Nightcore"
