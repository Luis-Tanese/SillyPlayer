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
    // flat active-band arrays avoid jagged Array<DoubleArray> indirection in the audio hot loop
    private var activeBands = IntArray(0)
    private var activeB0 = DoubleArray(0)
    private var activeB1 = DoubleArray(0)
    private var activeB2 = DoubleArray(0)
    private var activeA1 = DoubleArray(0)
    private var activeA2 = DoubleArray(0)
    private var filtering = false
    private var previous = EqSettings()
    private var preamp = 1.0
    private var scratchShorts = ShortArray(0)
    private var scratchSamples = DoubleArray(0)

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
        val freqs = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val bands = IntArray(10)
        val b0 = DoubleArray(10)
        val b1 = DoubleArray(10)
        val b2 = DoubleArray(10)
        val a1 = DoubleArray(10)
        val a2 = DoubleArray(10)
        var count = 0
        if (value.enabled) {
            for (i in 0 until 10) {
                val gain = value.gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f).toDouble()
                val f = freqs[i]
                if (gain == 0.0 || f >= rate * .49) continue
                val a = 10.0.pow(gain / 40.0)
                val w = 2.0 * PI * f / rate
                val alpha = sin(w) / 2.0
                val d = 1.0 + alpha / a
                val cosW = -2.0 * cos(w) / d
                bands[count] = i
                b0[count] = (1.0 + alpha * a) / d
                b1[count] = cosW
                b2[count] = (1.0 - alpha * a) / d
                a1[count] = cosW
                a2[count] = (1.0 - alpha / a) / d
                count++
            }
        }
        activeBands = bands.copyOf(count)
        activeB0 = b0.copyOf(count)
        activeB1 = b1.copyOf(count)
        activeB2 = b2.copyOf(count)
        activeA1 = a1.copyOf(count)
        activeA2 = a2.copyOf(count)
        filtering = value.enabled && count > 0
        preamp =
            if (filtering) 10.0.pow(-(value.gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f) / 20.0)
            else 1.0
    }

    private fun flushDenormal(z: Double): Double = if (abs(z) < 1e-15) 0.0 else z

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 polls with AudioProcessor.EMPTY_BUFFER before PCM arrives and while draining BaseAudioProcessor initially shares that same buffer: allocating zero output then copying input would be a forbidden self-copy, even with no bytes remaining
        if (!inputBuffer.hasRemaining()) return
        val latest = settings.get()
        if (latest !== previous && latest != previous) update(latest)
        val remainingBytes = inputBuffer.remaining()
        val output = replaceOutputBuffer(remainingBytes).order(ByteOrder.nativeOrder())
        val gain = signalGain.takeIf { it.isFinite() }?.coerceIn(1f, 3f) ?: 1f
        val isFiltering = filtering
        if (!isFiltering && gain == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val sampleCount = remainingBytes / 2
        if (sampleCount == 0) {
            output.flip()
            return
        }
        if (scratchShorts.size < sampleCount) scratchShorts = ShortArray(sampleCount)
        val shorts = scratchShorts
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.asShortBuffer().get(shorts, 0, sampleCount)
        inputBuffer.position(inputBuffer.position() + sampleCount * 2)

        if (!isFiltering) {
            // gain-only fast path stays in float with no biquad branching
            for (i in 0 until sampleCount) {
                val scaled = (shorts[i].toFloat() * gain).roundToInt()
                shorts[i] = scaled.coerceIn(-32768, 32767).toShort()
            }
        } else {
            if (scratchSamples.size < sampleCount) scratchSamples = DoubleArray(sampleCount)
            val samples = scratchSamples
            val scaleIn = preamp / 32768.0
            for (i in 0 until sampleCount) samples[i] = shorts[i].toDouble() * scaleIn

            val bands = activeBands
            val b0Arr = activeB0
            val b1Arr = activeB1
            val b2Arr = activeB2
            val a1Arr = activeA1
            val a2Arr = activeA2
            val d = delay
            val ch = channels

            // band-outer loop keeps biquad coefficients and delay states in registers across the whole buffer
            if (ch == 2) {
                val evenCount = sampleCount and 1.inv()
                for (bi in bands.indices) {
                    val band = bands[bi]
                    val cb0 = b0Arr[bi]
                    val cb1 = b1Arr[bi]
                    val cb2 = b2Arr[bi]
                    val ca1 = a1Arr[bi]
                    val ca2 = a2Arr[bi]
                    val kL = band * 2
                    val kR = (10 + band) * 2
                    var z1L = d[kL]
                    var z2L = d[kL + 1]
                    var z1R = d[kR]
                    var z2R = d[kR + 1]
                    var i = 0
                    while (i < evenCount) {
                        val sL = samples[i]
                        val rL = cb0 * sL + z1L
                        z1L = cb1 * sL - ca1 * rL + z2L
                        z2L = cb2 * sL - ca2 * rL
                        samples[i] = rL

                        val sR = samples[i + 1]
                        val rR = cb0 * sR + z1R
                        z1R = cb1 * sR - ca1 * rR + z2R
                        z2R = cb2 * sR - ca2 * rR
                        samples[i + 1] = rR
                        i += 2
                    }
                    d[kL] = flushDenormal(z1L)
                    d[kL + 1] = flushDenormal(z2L)
                    d[kR] = flushDenormal(z1R)
                    d[kR + 1] = flushDenormal(z2R)
                }
            } else if (ch > 0) {
                for (bi in bands.indices) {
                    val band = bands[bi]
                    val cb0 = b0Arr[bi]
                    val cb1 = b1Arr[bi]
                    val cb2 = b2Arr[bi]
                    val ca1 = a1Arr[bi]
                    val ca2 = a2Arr[bi]
                    for (c in 0 until ch) {
                        val k = (c * 10 + band) * 2
                        var z1 = d[k]
                        var z2 = d[k + 1]
                        var i = c
                        while (i < sampleCount) {
                            val s = samples[i]
                            val r = cb0 * s + z1
                            z1 = cb1 * s - ca1 * r + z2
                            z2 = cb2 * s - ca2 * r
                            samples[i] = r
                            i += ch
                        }
                        d[k] = flushDenormal(z1)
                        d[k + 1] = flushDenormal(z2)
                    }
                }
            }

            val scaleOut = gain.toDouble() * 32768.0
            for (i in 0 until sampleCount) {
                shorts[i] = (samples[i] * scaleOut).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }

        output.asShortBuffer().put(shorts, 0, sampleCount)
        output.position(output.position() + sampleCount * 2)
        output.flip()
    }

    override fun onFlush() {
        delay.fill(0.0)
    }
}
