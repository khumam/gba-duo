package com.khumam.gbaduo.emu

import android.content.Context

/**
 * Ambient colour (Quick menu > Ambient colour): the richer, warmer colour grade Paper view uses, applied to the plain
 * and Wide pictures too. Off until the player turns it on; one setting for every game.
 */
object AmbientSettings {
    private const val PREFS = "ambient"
    private const val KEY_ON = "on"

    fun on(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, enabled).apply()
    }
}
