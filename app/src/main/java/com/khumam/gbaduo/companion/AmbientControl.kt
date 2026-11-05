package com.khumam.gbaduo.companion

/**
 * Ambient on the companion screen: the pack's own look for its game (game.lua's `ambient()`: colour grade and light) on
 * the plain and Wide pictures. Only a pack's button turns it on or off: it calls the reserved action [CALL]
 * (`"onClick": { "call": "ambientView", "args": ["toggle"] }`; `"on"`, `"off"` or `"toggle"`, no argument = toggle). The app
 * handles it itself, not the pack's Lua, and every snapshot carries the state as `_ambient` (1 when on, else 0) and
 * `_ambientLabel` (`ON`, `OFF`, or `N/A` when the pack has no `ambient()`). It is kept per game, like Wide view.
 */
object AmbientControl {
    const val CALL = "ambientView"
    const val AMBIENT_KEY = "_ambient"
    const val LABEL_KEY = "_ambientLabel"

    fun handles(callName: String): Boolean = callName == CALL

    /** Whether ambient colour is on after the call, given it is [current] now. */
    fun next(current: Boolean, args: List<Any?>): Boolean = when (args.firstOrNull()?.toString()) {
        "on" -> true
        "off" -> false
        else -> !current
    }

    fun put(values: MutableMap<String, Any?>, on: Boolean, available: Boolean = true) {
        values[AMBIENT_KEY] = if (on && available) 1L else 0L
        values[LABEL_KEY] = if (!available) "N/A" else if (on) "ON" else "OFF"
    }
}
