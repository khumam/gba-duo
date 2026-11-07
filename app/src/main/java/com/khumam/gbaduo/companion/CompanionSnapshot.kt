package com.khumam.gbaduo.companion

/**
 * Builds the value tree handed to the widget renderer each tick: the pack's
 * JSON-only `values` (flat `Long`s) plus the built-ins `_frameCounter`, `_wide` and `_wideLabel` ([WideControl]), `_ambient` and `_ambientLabel` ([AmbientControl]), `_writePerm` and `_writePermLabel` ([WritePermissionControl]), with the
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
        ambient: Boolean = false,
        ambientAvailable: Boolean = true,
        writePermission: WritePermission = WritePermission.NONE,
    ): Map<String, Any?> {
        val values: MutableMap<String, Any?> = ValuesReader.readAll(manifest.values, memory).toMutableMap()
        values[FRAME_COUNTER_KEY] = frameCounter
        WideControl.put(values, wide)
        AmbientControl.put(values, ambient, ambientAvailable)
        WritePermissionControl.put(values, writePermission)
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
