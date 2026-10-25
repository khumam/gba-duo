package com.khumam.gbaduo.library.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Android image work: shrinking what's downloaded or read for the cache, and decoding it for the shelf. */
object ArtworkImages {
    private const val MAX_SIDE = 640
    private const val MIN_SIDE = 64

    /** Scaled to at most [MAX_SIDE] on the long side and re-encoded (PNG with transparency, else JPEG), or null if unusable. */
    fun encode(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_SIDE || bounds.outHeight < MIN_SIDE) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), MAX_SIDE) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val long = max(decoded.width, decoded.height)
        val bitmap = if (long > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / long
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else decoded
        val out = ByteArrayOutputStream()
        val ok = if (bitmap.hasAlpha()) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return if (ok) out.toByteArray() else null
    }

    /** A cached image decoded for a view of about [maxWidth] x [maxHeight] pixels. */
    fun decode(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = max(sampleSize(bounds.outWidth, maxWidth), sampleSize(bounds.outHeight, maxHeight))
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A power of two that keeps [size] at or above [target] after the division. */
    private fun sampleSize(size: Int, target: Int): Int {
        var sample = 1
        while (size / (sample * 2) >= target) sample *= 2
        return sample
    }
}
