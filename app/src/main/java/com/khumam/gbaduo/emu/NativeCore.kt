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

    /** Raw bus reads, any GBA address (including ROM 0x08000000+). 0 if no core loaded. */
    external fun rawRead8(address: Long): Int
    external fun rawRead16(address: Long): Int
    external fun rawRead32(address: Long): Long

    /** Frames run so far on the current core. 0 if no core loaded. */
    external fun frameCounter(): Long

    /**
     * Replaces any currently-loaded pack script with [source], run in a sandboxed Lua
     * state ([packDir] is where `pack.require` looks for sibling `.lua` modules). Runs
     * the script's top level, then its `init()` if defined. False on any failure —
     * check [lastScriptError] for why.
     */
    external fun loadScript(source: String, packDir: String): Boolean

    /** Calls the loaded script's `update()`; returns its JSON-serialized table, or null on error. */
    external fun tickScript(): String?

    /** Reason the last [loadScript]/[tickScript] call failed, if any. */
    external fun lastScriptError(): String?

    external fun unloadScript()

    /** `[width, height, ...ARGB8888 pixels]` for a `"gfx:..."` key from `gfx.decode`, or null if unknown. */
    external fun nativeImage(key: String): IntArray?

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
