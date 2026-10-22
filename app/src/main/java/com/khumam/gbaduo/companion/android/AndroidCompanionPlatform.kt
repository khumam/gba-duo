package com.khumam.gbaduo.companion.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.khumam.gbaduo.companion.LayoutParser
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.FontFace
import com.khumam.gbaduo.companion.ui.CompanionPlatform
import com.khumam.gbaduo.emu.NativeCore
import java.io.File

/**
 * Android side of the companion UI: pack fonts and images (every path checked to
 * stay inside the pack), `gfx:` sprites decoded by the pack's Lua script, and
 * blurred box shadows. Everything is loaded once and cached. Main thread only.
 */
class AndroidCompanionPlatform(private val packDir: File, private val fontFaces: List<FontFace>) : CompanionPlatform {

    private val families = HashMap<List<String>, FontFamily>()
    private val bitmaps = HashMap<File, ImageBitmap?>()
    // Sized like script.c's image cache (by pixels, not count), so every image a page
    // shows stays here instead of crossing JNI and becoming a new Bitmap each frame.
    private val sprites = object : LruCache<String, ImageBitmap>(SPRITE_CACHE_PIXELS) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height
    }

    override fun fontFamily(families: List<String>): FontFamily = this.families.getOrPut(families) {
        families.firstNotNullOfOrNull { family -> resolveFamily(family) } ?: FontFamily.Default
    }

    private fun resolveFamily(family: String): FontFamily? {
        if (family.startsWith(LayoutParser.ASSET_FONT_PREFIX)) {
            val file = packFile("assets/" + family.removePrefix(LayoutParser.ASSET_FONT_PREFIX)) ?: return null
            return FontFamily(Font(file))
        }
        val faces = fontFaces.filter { it.family.equals(family, ignoreCase = true) && it.file.isFile }
        if (faces.isNotEmpty()) {
            // Every declared weight/style of the family; TextStyle's weight then picks the closest.
            return FontFamily(faces.map { Font(it.file, FontWeight(it.weight.coerceIn(1, 1000)), if (it.italic) FontStyle.Italic else FontStyle.Normal) })
        }
        return when (family.lowercase()) {
            "serif" -> FontFamily.Serif
            "sans-serif", "system-ui", "ui-sans-serif" -> FontFamily.SansSerif
            "monospace", "ui-monospace" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> null
        }
    }

    override fun cssImage(url: String, baseDir: File?): ImageBitmap? = packFile(url, baseDir)?.let { bitmap(it) }

    /** An `asset:` path (relative to the pack's `assets/`) or a `gfx:` sprite key; null if not available (yet). */
    fun loadImage(key: String): ImageBitmap? = when {
        key.startsWith("asset:") -> packFile("assets/" + key.removePrefix("asset:"))?.let { bitmap(it) }
        key.startsWith("gfx:") -> sprite(key)
        else -> null
    }

    override fun setBlur(paint: Paint, radius: Float) {
        // A shadow layer is the blur Android's hardware renderer supports for shapes (API 28+).
        paint.asFrameworkPaint().setShadowLayer(radius, 0f, 0f, paint.color.toArgb())
    }

    private fun packFile(relativePath: String, baseDir: File? = null): File? =
        CssParser.resolveUrl(relativePath, baseDir ?: packDir, packDir)?.takeIf { it.isFile }

    private fun bitmap(file: File): ImageBitmap? = bitmaps.getOrPut(file) {
        runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.onFailure { Log.w(TAG, "Bad image $file", it) }.getOrNull()
    }

    private fun sprite(key: String): ImageBitmap? {
        sprites.get(key)?.let { return it }
        val data = NativeCore.nativeImage(key) ?: return null
        val width = data[0]
        val height = data[1]
        if (width <= 0 || height <= 0 || data.size < 2 + width * height) return null
        val image = Bitmap.createBitmap(data, 2, width, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
        sprites.put(key, image)
        return image
    }

    private companion object {
        const val TAG = "CompanionPlatform"
        /** script.c's IMAGE_CACHE_MAX_PIXELS: 4M px, 16 MB of ARGB. */
        const val SPRITE_CACHE_PIXELS = 4 * 1024 * 1024
    }
}
