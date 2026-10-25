package com.khumam.gbaduo.library.artwork

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.khumam.gbaduo.library.RomEntry
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.library.ui.CartridgeView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Gets cached artwork onto the shelf's cartridges without touching the main thread:
 * the ROM's CRC comes from the library's cache only (never hashed here), the image
 * is decoded on a worker and kept in a small memory cache. A game without art, or whose
 * ROM hasn't been hashed yet, simply keeps its drawn label.
 */
class ArtworkLoader(context: Context) {
    private val app = context.applicationContext
    private val library = RomLibrary(app)
    private val store = Artwork.store(app)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val density = app.resources.displayMetrics.density

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(48 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val keyByFile = ConcurrentHashMap<String, String>()
    private var generation = -1L

    /** Drops cached images if the store changed since last time. True when it did. */
    fun refresh(): Boolean {
        val now = store.generation()
        if (now == generation) return false
        generation = now
        cache.evictAll()
        keyByFile.clear()
        return true
    }

    fun bind(view: CartridgeView, entry: RomEntry) {
        view.setTag(TAG, entry.fileName)
        val known = keyByFile[entry.fileName]?.let { cache.get(it) }
        view.art = known
        if (known != null) return
        executor.execute {
            val crc = library.cachedCrc32(entry) ?: return@execute
            val key = "${entry.platform.id}/$crc"
            val bitmap = cache.get(key) ?: store.file(entry.platform.id, crc)?.let {
                ArtworkImages.decode(it, (ART_W_DP * density).toInt(), (ART_H_DP * density).toInt())
            }?.also { cache.put(key, it) }
            if (bitmap == null) return@execute
            keyByFile[entry.fileName] = key
            main.post { if (view.getTag(TAG) == entry.fileName) view.art = bitmap }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private companion object {
        val TAG = com.khumam.gbaduo.R.id.artwork_entry
        const val ART_W_DP = 180f
        const val ART_H_DP = 200f
    }
}
