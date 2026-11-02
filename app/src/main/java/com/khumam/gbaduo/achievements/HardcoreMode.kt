package com.khumam.gbaduo.achievements

import android.content.Context

/**
 * Settings > RetroAchievements > Hardcore mode: off by default. While on, a game started
 * gets no save or load states, no fast-forward, and no pack that can change the game.
 * A game reads this once as it starts, so flipping it never changes a game in progress.
 */
object HardcoreMode {
    private const val PREFS = "retroachievements"
    private const val KEY_HARDCORE = "hardcore"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HARDCORE, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HARDCORE, on).apply()
    }
}
