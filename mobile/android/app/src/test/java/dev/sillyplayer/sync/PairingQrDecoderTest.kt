package dev.sillyplayer.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PairingQrDecoderTest {
    private val sampleLink =
        "sillyplayer://pair?v=1&host=192.168.1.42:43817&fp=" +
            "a".repeat(64) +
            "&token=AbCdEfGhIjKlMnOpQrStUvWxYz012345"

    private fun renderQrFrame(
        payload: String,
        frameWidth: Int,
        frameHeight: Int,
        qrSize: Int,
        offsetX: Int = (frameWidth - qrSize) / 2,
        offsetY: Int = (frameHeight - qrSize) / 2,
        inverted: Boolean = false,
        rowPadding: Int = 0,
    ): Pair<ByteBuffer, Int> {
        val matrix =
            QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                qrSize,
                qrSize,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                    EncodeHintType.MARGIN to 4,
                ),
            )
        val rowStride = frameWidth + rowPadding
        val bytes = ByteArray(rowStride * frameHeight) { 128.toByte() }
        val dark = (if (inverted) 240 else 15).toByte()
        val light = (if (inverted) 15 else 240).toByte()
        for (y in 0 until matrix.height) {
            val fy = offsetY + y
            if (fy !in 0 until frameHeight) continue
            for (x in 0 until matrix.width) {
                val fx = offsetX + x
                if (fx !in 0 until frameWidth) continue
                bytes[fy * rowStride + fx] = if (matrix[x, y]) dark else light
            }
        }
        return ByteBuffer.wrap(bytes) to rowStride
    }

    @Test
    fun decodesCenteredHighDensityPairingQrOnFirstFrame() {
        val decoder = PairingQrDecoder()
        val (buffer, stride) = renderQrFrame(sampleLink, 1280, 720, 480)
        val decoded = decoder.decode(buffer, 1280, 720, stride, 1)
        assertEquals(sampleLink, decoded)
        assertEquals(sampleLink, validPairingQr(decoded!!))
    }

    @Test
    fun decodesPaddedStrideAndOffCenterQr() {
        val decoder = PairingQrDecoder()
        val (buffer, stride) =
            renderQrFrame(
                sampleLink,
                frameWidth = 1280,
                frameHeight = 720,
                qrSize = 360,
                offsetX = 40,
                offsetY = 40,
                rowPadding = 64,
            )
        val decoded = decoder.decode(buffer, 1280, 720, stride, 1)
        assertEquals(sampleLink, decoded)
    }

    @Test
    fun decodesInvertedQrWithinTwoFrames() {
        val decoder = PairingQrDecoder()
        val (buffer, stride) = renderQrFrame(sampleLink, 1280, 720, 480, inverted = true)
        var decoded: String? = null
        for (attempt in 0 until 2) {
            buffer.rewind()
            decoded = decoder.decode(buffer, 1280, 720, stride, 1)
            if (decoded != null) break
        }
        assertEquals(sampleLink, decoded)
    }

    @Test
    fun validatesPairingQrFormatStrictly() {
        assertEquals(sampleLink, validPairingQr("  $sampleLink  "))
        assertNull(validPairingQr("https://example.com"))
        assertNull(validPairingQr("sillyplayer://pair?v=2&host=192.168.1.42:43817&fp=${"a".repeat(64)}&token=AbCdEfGhIjKlMnOpQrSt"))
        assertNull(validPairingQr("sillyplayer://pair?v=1&host=999.1.1.1:43817&fp=${"a".repeat(64)}&token=AbCdEfGhIjKlMnOpQrSt"))
        assertNull(validPairingQr("sillyplayer://pair?v=1&host=192.168.1.42:43817&fp=short&token=AbCdEfGhIjKlMnOpQrSt"))
    }
}
