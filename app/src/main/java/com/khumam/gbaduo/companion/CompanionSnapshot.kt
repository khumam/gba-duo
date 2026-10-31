package com.khumam.gbaduo.companion

/**
 * Builds the value tree handed to the widget renderer each tick: the pack's
 * JSON-only `values` (flat `Long`s) plus the built-ins `_frameCounter`, `_wide` and `_wideLabel` ([WideControl]), with the
 * active Lua script's `update()` result merged on top (Lua wins on key clash,
 * per the design spec) when a script is loaded for this pack.
 */
object CompanionSnapshot {
    const val FRAME_COUNTER_KEY = "_frameCounter"

    fun build(
        manifest: PackManifest,
        memory: MemoryReader,
        frameCounter: Long,
        scriptLoaded: Boolean,
        wide: WideState = WideState.NONE,
    ): Map<String, Any?> {
        val values: MutableMap<String, Any?> = ValuesReader.readAll(manifest.values, memory).toMutableMap()
        values[FRAME_COUNTER_KEY] = frameCounter
        WideControl.put(values, wide)
        if (scriptLoaded) {
            val scripted = LuaScript.tick()
            if (scripted != null) {
                values.putAll(scripted)
            } else {
                values["_scriptError"] = LuaScript.lastError()
            }
        }
        return values
    }
}
