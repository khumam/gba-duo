package com.khumam.gbaduo.emu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer

/** One decoded lookup texture of a preset: RGBA bytes, row 0 on top. */
class LutImage(val id: String, val width: Int, val height: Int, val pixels: ByteBuffer)

/**
 * The libretro slang pass chain (native, `slang_chain.cpp`). The work is split by thread:
 * [prepare] parses and translates a preset (CPU only, any thread), [instantiate] builds the GL objects (GL thread),
 * [render] and [destroy] run on the GL thread too. A 0 handle means failure; [lastError] says why (per calling thread).
 */
object SlangChain {
    init {
        System.loadLibrary("gbaduo")
    }

    /** A failure on the Kotlin side of [prepare] (a lookup texture that won't decode), which the native error can't know. */
    private val localError = ThreadLocal<String?>()

    /** Why the last call on this thread failed. */
    fun lastError(): String = localError.get() ?: nativeLastError()

    private external fun nativeLastError(): String
    private external fun textureList(root: String, preset: String): Array<String>?
    private external fun prepare(root: String, preset: String, ids: Array<String>, sizes: IntArray, pixels: Array<ByteBuffer>): Long

    /** Frees a plan that is not going to be instantiated. */
    external fun discard(plan: Long)

    /** GL thread. Consumes the plan, whether or not it succeeds. */
    external fun instantiate(plan: Long): Long

    /** GL thread. Draws the chain's result for [inputTexture] into [outputTexture] (RGBA8, already allocated). */
    external fun render(
        chain: Long, inputTexture: Int, inputWidth: Int, inputHeight: Int,
        outputTexture: Int, outputWidth: Int, outputHeight: Int, frame: Int,
    )

    external fun destroy(chain: Long)

    /** After the GL context was lost (its objects went with it): frees the chain with no GL call. */
    external fun abandon(chain: Long)

    /**
     * Parses and translates [presetPath] (relative to [root]) and decodes its lookup textures. Any thread.
     * Returns a plan handle for [instantiate], or 0 with [lastError] set.
     */
    fun prepare(root: File, presetPath: String): Long {
        localError.set(null)
        val list = textureList(root.path, presetPath) ?: return 0
        val luts = decodeLuts(root, list) ?: return 0
        return prepare(root.path, presetPath, luts.map { it.id }.toTypedArray(),
            IntArray(luts.size * 2) { if (it % 2 == 0) luts[it / 2].width else luts[it / 2].height },
            luts.map { it.pixels }.toTypedArray())
    }

    private fun decodeLuts(root: File, idsAndPaths: Array<String>): List<LutImage>? {
        val out = ArrayList<LutImage>()
        for (i in idsAndPaths.indices step 2) {
            val path = idsAndPaths[i + 1]
            val image = runCatching { decode(idsAndPaths[i], File(root, path)) }.getOrElse { e ->
                localError.set("cannot read image $path: ${e.message ?: e.javaClass.simpleName}")
                return null
            }
            if (image == null) {
                localError.set("cannot read image $path")
                return null
            }
            out += image
        }
        return out
    }

    private fun decode(id: String, file: File): LutImage? {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        // 16-bit PNGs decode to RGBA_F16 (8 bytes a pixel), some greyscale ones to other configs: the native side wants RGBA8.
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
            ?: return null
        try {
            val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            return LutImage(id, bitmap.width, bitmap.height, buffer)
        } finally {
            bitmap.recycle()
        }
    }
}
