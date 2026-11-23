package dev.sillyplayer.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

data class EqSettings(val enabled: Boolean = false, val gains: List<Float> = List(10) { 0f })

class EqualizerProcessor : BaseAudioProcessor() {
    val settings = AtomicReference(EqSettings())
    @Volatile var signalGain: Float = 1f
    private var channels = 2
    private var rate = 48000
    private var delay = DoubleArray(40)
    private var coefficients = Array(10) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }
    private var activeBands = IntArray(0)
    private var filtering = false
    private var previous = EqSettings()
    private var preamp = 1.0

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        delay = DoubleArray(channels * 20)
        previous = EqSettings()
        update(settings.get())
        return inputAudioFormat
    }

    private fun update(value: EqSettings) {
        previous = value
        val freqs = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val enabledBands = ArrayList<Int>(10)
        coefficients =
            Array(10) { i ->
                val gain =
                    if (value.enabled)
                        value.gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f).toDouble()
                    else 0.0
                val f = freqs[i]
                if (gain == 0.0 || f >= rate * .49) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
                else {
                    enabledBands += i
                    val a = 10.0.pow(gain / 40)
                    val w = 2 * PI * f / rate
                    val alpha = sin(w) / 2
                    val d = 1 + alpha / a
                    doubleArrayOf(
                        (1 + alpha * a) / d,
                        -2 * cos(w) / d,
                        (1 - alpha * a) / d,
                        -2 * cos(w) / d,
                        (1 - alpha / a) / d,
                    )
                }
            }
        activeBands = enabledBands.toIntArray()
        filtering = value.enabled && activeBands.isNotEmpty()
        preamp =
            if (filtering) 10.0.pow(-(value.gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f) / 20.0)
            else 1.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 polls with AudioProcessor.EMPTY_BUFFER before PCM arrives and while draining BaseAudioProcessor initially shares that same buffer: allocating zero output then copying input would be a forbidden self-copy, even with no bytes remaining
        if (!inputBuffer.hasRemaining()) return
        val latest = settings.get()
        if (latest != previous) update(latest)
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val gain = signalGain.takeIf { it.isFinite() }?.coerceIn(1f, 3f) ?: 1f
        val isFiltering = filtering
        if (!isFiltering && gain == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        val bands = activeBands
        val scaleIn = (if (isFiltering) preamp else 1.0) / 32768.0
        val scaleOut = gain.toDouble() * 32768.0
        var channel = 0
        while (inputBuffer.remaining() >= 2) {
            var sample = inputBuffer.short.toDouble() * scaleIn
            if (isFiltering) {
                val channelOffset = channel * 10
                for (i in bands) {
                    val c = coefficients[i]
                    val k = (channelOffset + i) * 2
                    val result = c[0] * sample + delay[k]
                    delay[k] = c[1] * sample - c[3] * result + delay[k + 1]
                    delay[k + 1] = c[2] * sample - c[4] * result
                    sample = result
                }
            }
            output.putShort((sample * scaleOut).roundToInt().coerceIn(-32768, 32767).toShort())
            channel++
            if (channel >= channels) channel = 0
        }
        output.flip()
    }

    override fun onFlush() {
        delay.fill(0.0)
    }
}
