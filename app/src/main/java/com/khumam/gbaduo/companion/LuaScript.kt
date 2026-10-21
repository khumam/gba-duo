package com.khumam.gbaduo.companion

import com.khumam.gbaduo.emu.NativeCore
import org.json.JSONTokener

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

    /** Hands the companion screen's UI state to the script, as the `ui` table `update(ui)` receives. */
    fun setUi(ui: Map<String, Any?>) {
        NativeCore.setScriptUi(ui.keys.toTypedArray(), ui.values.map(::scalar).toTypedArray())
    }

    /**
     * Calls `actions.<name>(args...)`. Returns what it returned if that was a table
     * (UI actions to apply, e.g. `{ navigate = "detail" }` or a list of them), else null; on an error
     * also null, with the reason in [lastError]. [mayWrite]: see [NativeCore.callScriptAction].
     */
    fun call(name: String, args: List<Any?>, mayWrite: Boolean = false): Any? {
        val json = NativeCore.callScriptAction(name, args.map(::scalar).toTypedArray(), mayWrite) ?: return null
        return runCatching { JsonTree.convertValue(JSONTokener(json).nextValue()) }.getOrNull()
    }

    /** Lua gets strings, numbers and booleans; anything else becomes nil. */
    private fun scalar(value: Any?): Any? = when (value) {
        is String, is Long, is Double, is Boolean -> value
        is Int -> value.toLong()
        is Float -> value.toDouble()
        else -> null
    }

    fun lastError(): String? = NativeCore.lastScriptError()?.takeIf { it.isNotEmpty() }

    fun unload() = NativeCore.unloadScript()
}
