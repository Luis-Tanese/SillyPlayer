package dev.sillyplayer.online

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/** validate each provider candidate before embedding it as JPEG */
fun downloadCover(http: OkHttpClient, url: String, directory: File, youtubeId: String = ""): File? {
    val limit = 8L * 1024 * 1024
    for (candidate in thumbnailCandidates(url, youtubeId)) {
        val request = runCatching { Request.Builder().url(candidate).build() }.getOrNull() ?: continue
        try {
            val bytes = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > limit || body.source().request(limit + 1)) return@use null
                body.source().readByteArray()
            } ?: continue
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
            try {
                val jpeg = File(directory, "cover.jpg")
                if (jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }) return jpeg
            } finally {
                bitmap.recycle()
            }
        } catch (_: IOException) {
            // a transient failure on one image should still allow the remaining candidates
        }
    }
    return null
}
