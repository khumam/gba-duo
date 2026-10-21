package com.khumam.gbaduo.companion

import android.content.Context

/** App-wide companion settings, in Settings. */
object CompanionSettings {
    private const val PREFS = "companion"
    private const val KEY_ALLOW_WRITES = "allow_memory_writes"

    /**
     * "Allow packs to write to the game": off by default. While off, no pack can change
     * a game, whatever its manifest asks for or the player answered before.
     */
    fun allowWrites(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALLOW_WRITES, false)

    fun setAllowWrites(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALLOW_WRITES, allowed).apply()
    }
}
