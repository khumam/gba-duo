package com.khumam.gbaduo.companion.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import com.khumam.gbaduo.companion.LayoutParser
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.FontFace
import com.khumam.gbaduo.emu.NativeCore
import java.io.File
import kotlin.math.abs

/**
 * Per-pack cache of fonts, image files and decoded `gfx:` sprites, so nothing is
 * loaded or decoded more than once per pack. Every file path is checked to stay
 * inside the pack folder. Main thread only.
 */
class AssetCache(private val packDir: File, private val fontFaces: List<FontFace>) {

    private val typefaces = HashMap<File, Typeface?>()
    private val bitmaps = HashMap<File, Bitmap?>()
    private val sprites = object : LruCache<String, Bitmap>(SPRITE_CACHE_SIZE) {}

    /** The first family in [families] that resolves, styled to [weight]/[italic]. */
    fun typeface(families: List<String>, weight: Int, italic: Boolean): Typeface {
        for (family in families) {
            val base = when {
                family.startsWith(LayoutParser.ASSET_FONT_PREFIX) ->
                    packFile("assets/" + family.removePrefix(LayoutParser.ASSET_FONT_PREFIX))?.let { fontFile(it) }
                else -> fontFace(family, weight, italic) ?: genericFamily(family)
            } ?: continue
            return Typeface.create(base, weight.coerceIn(1, 1000), italic)
        }
        return Typeface.create(Typeface.DEFAULT, weight.coerceIn(1, 1000), italic)
    }

    private fun fontFace(family: String, weight: Int, italic: Boolean): Typeface? {
        val candidates = fontFaces.filter { it.family.equals(family, ignoreCase = true) }
        if (candidates.isEmpty()) return null
        val best = candidates.minByOrNull { abs(it.weight - weight) + if (it.italic == italic) 0 else 1000 } ?: return null
        return fontFile(best.file)
    }

    private fun genericFamily(family: String): Typeface? = when (family.lowercase()) {
        "serif" -> Typeface.SERIF
        "sans-serif", "system-ui", "ui-sans-serif" -> Typeface.SANS_SERIF
        "monospace", "ui-monospace" -> Typeface.MONOSPACE
        else -> null
    }

    private fun fontFile(file: File): Typeface? = typefaces.getOrPut(file) {
        if (!file.isFile) null else runCatching { Typeface.createFromFile(file) }.onFailure { Log.w(TAG, "Bad font $file", it) }.getOrNull()
    }

    /** A pack file (relative to the pack root) or null if it doesn't exist or would leave the pack. */
    fun packFile(relativePath: String, baseDir: File? = null): File? =
        CssParser.resolveUrl(relativePath, baseDir ?: packDir, packDir)?.takeIf { it.isFile }

    fun bitmap(file: File): Bitmap? = bitmaps.getOrPut(file) {
        runCatching { BitmapFactory.decodeFile(file.path) }.onFailure { Log.w(TAG, "Bad image $file", it) }.getOrNull()
    }

    /** A `gfx:` sprite decoded by the pack's Lua script; decoded once per key. */
    fun sprite(key: String): Bitmap? {
        sprites.get(key)?.let { return it }
        val data = NativeCore.nativeImage(key) ?: return null
        val width = data[0]
        val height = data[1]
        if (width <= 0 || height <= 0 || data.size < 2 + width * height) return null
        val bitmap = Bitmap.createBitmap(data, 2, width, width, height, Bitmap.Config.ARGB_8888)
        sprites.put(key, bitmap)
        return bitmap
    }

    private companion object {
        const val TAG = "AssetCache"
        const val SPRITE_CACHE_SIZE = 64
    }
}
