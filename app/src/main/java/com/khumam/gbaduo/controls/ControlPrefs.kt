package com.khumam.gbaduo.controls

import android.content.Context

/** The saved [ControlScheme]: SharedPreferences file `controls`, key `scheme`. */
object ControlPrefs {
    private const val PREFS = "controls"
    private const val KEY = "scheme"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Never throws: a missing or broken value is the defaults. */
    fun load(context: Context): ControlScheme =
        runCatching { ControlSchemeJson.decode(prefs(context).getString(KEY, null)) }.getOrDefault(ControlScheme.DEFAULT)

    fun save(context: Context, scheme: ControlScheme) {
        prefs(context).edit().putString(KEY, ControlSchemeJson.encode(scheme)).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
