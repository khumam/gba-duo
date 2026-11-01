package com.khumam.gbaduo.companion

/** How much of the game's world Wide view paints: nothing, the sides, or all four sides with the game zoomed out. */
enum class WideMode { OFF, WIDE, EXTRA }

/** Which Wide view mode is on for this game, and whether the pack can paint it (its game.lua defines `wide()`). */
data class WideState(val mode: WideMode, val available: Boolean) {
    constructor(enabled: Boolean, available: Boolean) : this(if (enabled) WideMode.WIDE else WideMode.OFF, available)

    val enabled: Boolean get() = mode != WideMode.OFF

    companion object {
        val NONE = WideState(WideMode.OFF, available = false)
    }
}

/**
 * Wide view on the companion screen. A pack's button calls the reserved action [CALL] (`"onClick": {
 * "call": "wideView", "args": ["toggle"] }`; `"on"`, `"off"`, `"extra"` or `"toggle"`, no argument = toggle,
 * which steps off, wide, extra wide, off). The app handles it itself, not the pack's Lua, and every snapshot
 * carries the state as `_wide` (1 for wide or extra wide, else 0), `_wideMode` (0 off, 1 wide, 2 extra wide)
 * and `_wideLabel` (`ON`, `XL`, `OFF`, or `N/A` when the pack has no wide view) for the button to show.
 */
object WideControl {
    const val CALL = "wideView"
    const val WIDE_KEY = "_wide"
    const val MODE_KEY = "_wideMode"
    const val LABEL_KEY = "_wideLabel"

    fun handles(callName: String): Boolean = callName == CALL

    /** The mode the call asks for, given [current]. */
    fun next(current: WideMode, args: List<Any?>): WideMode = when (args.firstOrNull()?.toString()) {
        "on" -> WideMode.WIDE
        "off" -> WideMode.OFF
        "extra" -> WideMode.EXTRA
        else -> when (current) {
            WideMode.OFF -> WideMode.WIDE
            WideMode.WIDE -> WideMode.EXTRA
            WideMode.EXTRA -> WideMode.OFF
        }
    }

    fun put(values: MutableMap<String, Any?>, state: WideState) {
        val mode = if (state.available) state.mode else WideMode.OFF
        values[WIDE_KEY] = if (mode != WideMode.OFF) 1L else 0L
        values[MODE_KEY] = mode.ordinal.toLong()
        values[LABEL_KEY] = when {
            !state.available -> "N/A"
            mode == WideMode.EXTRA -> "XL"
            mode == WideMode.WIDE -> "ON"
            else -> "OFF"
        }
    }
}
