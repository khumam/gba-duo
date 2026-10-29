package com.khumam.gbaduo.emu

import android.content.Context

/**
 * Integer scaling (Settings > Integer scaling): every game pixel becomes an exact whole-number block, so
 * the picture is smaller and has bars. Off until the player turns it on; off means the full-size picture,
 * which GameView shrinks from a whole-number multiple so its pixels stay even. See [ScreenFit].
 */
object ScaleSettings {
    private const val PREFS = "scale"
    private const val KEY_INTEGER = "integer"

    fun integer(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INTEGER, false)

    fun setInteger(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_INTEGER, enabled).apply()
    }
}
