package com.mob8n.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.mob8n.core.NodeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** content:// or file:// image -> JPEG bytes, longest side <= 1568 px, q85 (lowered until <= 4 MB), for Claude image blocks. */
object Images {
    const val MAX_SIDE = 1568
    const val MAX_BYTES = 4 * 1024 * 1024

    suspend fun base64(context: Context, uri: String): String = Base64.encodeToString(jpeg(context, uri), Base64.NO_WRAP)

    suspend fun jpeg(context: Context, uri: String): ByteArray = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        val cr = context.contentResolver
        fun open() = try { cr.openInputStream(u) ?: throw NodeException("Cannot open image $uri") } catch (e: NodeException) { throw e } catch (e: Exception) { throw NodeException("Cannot open image $uri: ${e.message ?: e.javaClass.simpleName}") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw NodeException("Not a decodable image: $uri")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw NodeException("Cannot decode image $uri")
        val longest = max(bmp.width, bmp.height)
        if (longest > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
        }
        // ponytail: no EXIF rotation (screenshots/gallery previews are upright); upgrade = android.media.ExifInterface(InputStream) + Matrix.
        var q = 85
        var bytes: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            bytes = bos.toByteArray()
            q -= 10
        } while (bytes.size > MAX_BYTES && q >= 30)
        if (bytes.size > MAX_BYTES) throw NodeException("Image is too large even after compression (${bytes.size / 1024} KB)")
        bytes
    }
}
