package com.khumam.gbaduo.companion

/** Whether Wide view is on for this game, and whether the pack can paint it (its game.lua defines `wide()`). */
data class WideState(val enabled: Boolean, val available: Boolean) {
    companion object {
        val NONE = WideState(enabled = false, available = false)
    }
}

/**
 * Wide view on the companion screen. A pack's button calls the reserved action [CALL] (`"onClick": {
 * "call": "wideView", "args": ["toggle"] }`; `"on"`, `"off"` or `"toggle"`, no argument = toggle). The app
 * handles it itself, not the pack's Lua, and every snapshot carries the state as `_wide` (1 or 0) and
 * `_wideLabel` (`ON`, `OFF`, or `N/A` when the pack has no wide view) for the button to show.
 */
object WideControl {
    const val CALL = "wideView"
    const val WIDE_KEY = "_wide"
    const val LABEL_KEY = "_wideLabel"

    fun handles(callName: String): Boolean = callName == CALL

    /** The state the call asks for, given [current]. */
    fun next(current: Boolean, args: List<Any?>): Boolean = when (args.firstOrNull()?.toString()) {
        "on" -> true
        "off" -> false
        else -> !current
    }

    fun put(values: MutableMap<String, Any?>, state: WideState) {
        val on = state.enabled && state.available
        values[WIDE_KEY] = if (on) 1L else 0L
        values[LABEL_KEY] = when {
            !state.available -> "N/A"
            on -> "ON"
            else -> "OFF"
        }
    }
}
