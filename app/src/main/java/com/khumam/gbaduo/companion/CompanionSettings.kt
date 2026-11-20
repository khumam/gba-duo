package com.khumam.gbaduo.companion

import android.content.Context

/**
 * What the second screen does while a game runs (Settings > Companion screen). Only matters on a
 * device with a second screen; with one screen the companion is still called up over the game.
 */
enum class CompanionMode(val id: String) {
    /** No companion window at all: the second screen is free for other apps. */
    OFF("off"),

    /** All black with the game's cartridge faded in the middle; no interaction. */
    BLANK_ART("blank_art"),

    /** The pack's companion screen. */
    ACTIVE("active"),
    ;

    companion object {
        fun byId(id: String?): CompanionMode = entries.firstOrNull { it.id == id } ?: ACTIVE
    }
}

/** App-wide companion settings, in Settings. */
object CompanionSettings {
    private const val PREFS = "companion"
    private const val KEY_MODE = "mode"
    private const val KEY_ALLOW_WRITES = "allow_memory_writes"

    /**
     * "Allow packs to write to the game": off by default. While off, no pack can change
     * a game, whatever its manifest asks for or the player answered before.
     */
    fun allowWrites(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALLOW_WRITES, false)

    /** [CompanionMode.ACTIVE] until the player chooses another. */
    fun mode(context: Context): CompanionMode =
        CompanionMode.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: CompanionMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.id).apply()
    }

    fun setAllowWrites(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ALLOW_WRITES, allowed).apply()
    }
}
