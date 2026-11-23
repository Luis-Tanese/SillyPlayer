package dev.sillyplayer.sync

import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer

// multi-pass QR decoder for CameraX Y-plane frames, prioritizes the centered viewfinder then alternates full-frame/inverted passes
class PairingQrDecoder {
    private var pixels = ByteArray(0)
    private var frameCount = 0
    private val reader = QRCodeReader()
    private val fastHints: Map<DecodeHintType, Any> =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
    private val hardHints: Map<DecodeHintType, Any> =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
        )

    private fun tryDecode(bitmap: BinaryBitmap, hints: Map<DecodeHintType, Any>): String? =
        try {
            reader.decode(bitmap, hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }

    fun decode(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        left: Int = 0,
        top: Int = 0,
    ): String? {
        require(width in 1..4096 && height in 1..4096 && left >= 0 && top >= 0)
        require(rowStride > 0 && pixelStride > 0)
        val end =
            buffer.position().toLong() +
                (top.toLong() + height - 1) * rowStride +
                (left.toLong() + width - 1) * pixelStride
        require(end < buffer.limit()) { "Incomplete camera luminance plane" }
        val total = width * height
        if (pixels.size != total) pixels = ByteArray(total)
        val base = buffer.position()
        val source = buffer.duplicate()
        if (pixelStride == 1 && rowStride == width && left == 0 && top == 0) {
            source.position(base)
            source.get(pixels, 0, total)
        } else if (pixelStride == 1) {
            for (y in 0 until height) {
                source.position(base + (top + y) * rowStride + left)
                source.get(pixels, y * width, width)
            }
        } else {
            for (y in 0 until height) {
                val offset = base + (top + y) * rowStride + left * pixelStride
                for (x in 0 until width) pixels[y * width + x] =
                    source.get(offset + x * pixelStride)
            }
        }

        val frame = frameCount++
        val roiSize = (minOf(width, height) * 82 / 100).coerceIn(1, minOf(width, height))
        val roiLeft = (width - roiSize) / 2
        val roiTop = (height - roiSize) / 2
        val hasDistinctRoi = roiSize < width || roiSize < height

        val roiSource =
            if (hasDistinctRoi)
                PlanarYUVLuminanceSource(pixels, width, height, roiLeft, roiTop, roiSize, roiSize, false)
            else PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)

        // pass 1: fast global histogram on the centered viewfinder (fastest for bright desktop displays)
        tryDecode(BinaryBitmap(GlobalHistogramBinarizer(roiSource)), fastHints)?.let { return it }

        // pass 2: fast hybrid binarizer on the centered viewfinder (handles uneven lighting/glare)
        tryDecode(BinaryBitmap(HybridBinarizer(roiSource)), fastHints)?.let { return it }

        // pass 3: alternate between full-frame and hard/inverted passes so frame latency stays low
        if (frame % 2 == 0) {
            if (hasDistinctRoi) {
                val full = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                tryDecode(BinaryBitmap(HybridBinarizer(full)), fastHints)?.let { return it }
            }
            if (frame % 4 == 0) {
                val full = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                tryDecode(BinaryBitmap(HybridBinarizer(full)), hardHints)?.let { return it }
            }
        } else {
            tryDecode(BinaryBitmap(HybridBinarizer(roiSource)), hardHints)?.let { return it }
            // QRCodeReader does not handle ALSO_INVERTED internally, explicitly invert luminance for light-on-dark QRs
            tryDecode(BinaryBitmap(GlobalHistogramBinarizer(roiSource.invert())), fastHints)?.let { return it }
        }
        return null
    }
}

/** accept only a complete desktop pairing payload, without performing a network operation */
fun validPairingQr(text: String): String? =
    runCatching {
            val link = text.trim()
            require(link.length <= 4096)
            val uri = URI(link)
            require(uri.scheme == "sillyplayer" && uri.host == "pair" && uri.fragment == null)
            val fields =
                uri.rawQuery.orEmpty().split('&').map {
                    val parts = it.split('=', limit = 2)
                    require(parts.size == 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
            require(fields.map { it.first }.distinct().size == fields.size)
            val query = fields.toMap()
            require(query["v"] == "1")
            val host = query.getValue("host").split(':')
            require(host.size == 2 && host[1].toInt() in 1..65535)
            val ip = host[0].split('.')
            require(
                ip.size == 4 &&
                    ip.all { it.isNotBlank() && it.all(Char::isDigit) && it.toInt() in 0..255 }
            )
            require(query.getValue("fp").matches(Regex("[a-f0-9]{64}")))
            require(query.getValue("token").matches(Regex("[A-Za-z0-9_-]{16,512}")))
            link
        }
        .getOrNull()
