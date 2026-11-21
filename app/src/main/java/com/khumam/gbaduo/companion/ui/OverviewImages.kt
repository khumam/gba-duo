package com.khumam.gbaduo.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Overview pictures ready to draw: decoded near card size, scaled to cover the card and cropped from the top, so the
 * carousel only ever draws a small bitmap (the originals are over a thousand pixels tall). A few are kept in memory.
 * Blocking; call off the main thread. A miss is null.
 */
class OverviewImages(private val cache: OverviewCache) {
    private val memory = LruCache<String, Bitmap>(MAX_IMAGES)
    private val known = ConcurrentHashMap.newKeySet<String>()
    private val stale = ConcurrentHashMap.newKeySet<String>()

    /** [url]'s picture as exactly [width] x [height] pixels (less if the picture is smaller). */
    fun load(url: String, width: Int, height: Int): Bitmap? {
        known += url
        val force = stale.remove(url)
        val key = "$url@${width}x$height"
        if (!force) memory.get(key)?.let { return it }
        val bytes = cache.bytes(url, force) ?: return null
        val bitmap = decode(bytes, width, height) ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /** Every image seen so far is downloaded again the next time it is asked for. */
    fun invalidate() {
        memory.evictAll()
        stale.addAll(known)
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = OverviewGeometry.sampleSize(bounds.outWidth, width) }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val (scaledWidth, scaledHeight) = OverviewGeometry.coverSize(source.width, source.height, width, height)
        val scaled = if (scaledWidth == source.width && scaledHeight == source.height) source
        else Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val left = OverviewGeometry.cropLeft(scaled.width, width).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(scaled, left, 0, minOf(width, scaled.width - left), minOf(height, scaled.height))
        if (scaled !== source) source.recycle()
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private companion object {
        const val MAX_IMAGES = 24
    }
}
