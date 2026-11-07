package com.khumam.gbaduo.companion

/** Where a pack stands on changing this game: what its permission button shows. */
enum class WritePermission(val label: String) {
    /** The player said yes (for this version of the pack). */
    ALLOWED("ALLOWED"),

    /** The player said no. */
    DENIED("DENIED"),

    /** Not asked yet: the player is asked the first time the pack is about to change the game. */
    ASK("NOT ASKED"),

    /** The pack asks to change games, but Settings or hardcore mode keeps every pack from it. */
    OFF("OFF"),

    /** The pack never changes the game (no `memory-write` permission): nothing to ask. */
    NONE("N/A"),
}

/**
 * The player's answer to "let this pack change the game?", retaken on the pack's own page. A pack's button calls the
 * reserved action [CALL] (`"onClick": { "call": "writePermission" }`) and the player is asked again, right then; the
 * app handles it itself, not the pack's Lua. Every snapshot carries `_writePerm` (1 when allowed, else 0) and
 * `_writePermLabel` ([WritePermission.label]) for the button to show.
 */
object WritePermissionControl {
    const val CALL = "writePermission"
    const val ALLOWED_KEY = "_writePerm"
    const val LABEL_KEY = "_writePermLabel"

    fun handles(callName: String): Boolean = callName == CALL

    fun put(values: MutableMap<String, Any?>, state: WritePermission) {
        values[ALLOWED_KEY] = if (state == WritePermission.ALLOWED) 1L else 0L
        values[LABEL_KEY] = state.label
    }
}
