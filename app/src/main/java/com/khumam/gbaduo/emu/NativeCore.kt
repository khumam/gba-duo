package com.khumam.gbaduo.emu

import java.nio.ByteBuffer

/** Thin JNI wrapper around the native mGBA core. One loaded ROM at a time (process-global). */
object NativeCore {
    init {
        System.loadLibrary("gbaduo")
    }

    external fun selfTest(): Boolean

    /** Loads [rom] bytes and attaches/creates SRAM at [savePath]. False on failure (bad ROM). */
    external fun load(rom: ByteArray, savePath: String): Boolean

    /** Runs one frame with the given GBA key bitmask (see [GbaKeys]). */
    external fun runFrame(keys: Int)

    /** Direct RGBA8888 buffer, 240x160, valid until the next [unload]. */
    external fun videoBuffer(): ByteBuffer?

    /** Fills [out] (interleaved stereo s16) with as many frames as available; returns frame count. */
    external fun audio(out: ShortArray): Int

    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    external fun gameCode(): String
    external fun crc32(): Long
    external fun unload()
}

/** GBA key bitmask values (bit index matches mGBA's GBA_KEY_* enum). */
object GbaKeys {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
}
