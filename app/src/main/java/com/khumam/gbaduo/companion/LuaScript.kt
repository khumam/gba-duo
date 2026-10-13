package com.khumam.gbaduo.companion

import com.khumam.gbaduo.emu.NativeCore

/**
 * Thin wrapper over the native Lua sandbox (`NativeCore.loadScript`/`tickScript`).
 * One script active at a time, tied to the emu thread (not thread-safe to call
 * from elsewhere while a tick is running — see EmuThread, which owns both).
 */
object LuaScript {

    /** Loads and runs [source]'s top level, then its `init()` if defined. */
    fun load(source: String, packDir: String): Boolean = NativeCore.loadScript(source, packDir)

    /** Calls `update()`; returns the parsed table, or null (check [lastError]) on any failure. */
    fun tick(): Map<String, Any?>? {
        val json = NativeCore.tickScript() ?: return null
        return runCatching { JsonTree.parseObject(json) }.getOrNull()
    }

    fun lastError(): String? = NativeCore.lastScriptError()?.takeIf { it.isNotEmpty() }

    fun unload() = NativeCore.unloadScript()
}
