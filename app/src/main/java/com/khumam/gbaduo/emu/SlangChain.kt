package com.khumam.gbaduo.emu

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

    external fun lastError(): String
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

    /**
     * Parses and translates [presetPath] (relative to [root]) and decodes its lookup textures. Any thread.
     * Returns a plan handle for [instantiate], or 0 with [lastError] set.
     */
    fun prepare(root: File, presetPath: String): Long {
        val list = textureList(root.path, presetPath) ?: return 0
        val luts = decodeLuts(root, list) ?: return 0
        return prepare(root.path, presetPath, luts.map { it.id }.toTypedArray(),
            IntArray(luts.size * 2) { if (it % 2 == 0) luts[it / 2].width else luts[it / 2].height },
            luts.map { it.pixels }.toTypedArray())
    }

    private fun decodeLuts(root: File, idsAndPaths: Array<String>): List<LutImage>? {
        val out = ArrayList<LutImage>()
        for (i in idsAndPaths.indices step 2) {
            val file = File(root, idsAndPaths[i + 1])
            val options = BitmapFactory.Options().apply { inPremultiplied = false }
            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
            val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            out += LutImage(idsAndPaths[i], bitmap.width, bitmap.height, buffer)
            bitmap.recycle()
        }
        return out
    }
}
