package com.khumam.gbaduo.companion

/**
 * Ambient colour on the companion screen: the richer, warmer colour grade Paper view uses, on the plain and Wide pictures
 * too (Quick menu > Ambient colour does the same). A pack's button calls the reserved action [CALL]
 * (`"onClick": { "call": "ambientView", "args": ["toggle"] }`; `"on"`, `"off"` or `"toggle"`, no argument = toggle). The app
 * handles it itself, not the pack's Lua, and every snapshot carries the state as `_ambient` (1 when on, else 0) and
 * `_ambientLabel` (`ON` or `OFF`). It is one setting for every game.
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

    fun put(values: MutableMap<String, Any?>, on: Boolean) {
        values[AMBIENT_KEY] = if (on) 1L else 0L
        values[LABEL_KEY] = if (on) "ON" else "OFF"
    }
}
