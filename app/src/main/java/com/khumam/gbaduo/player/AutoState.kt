package com.khumam.gbaduo.player

import android.content.Context

/** App-wide Auto save and Auto load, in Settings: both off by default. */
object AutoState {
    private const val PREFS = "player"
    private const val KEY_SAVE = "auto_save"
    private const val KEY_LOAD = "auto_load"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE, false)
    fun setSave(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SAVE, on).apply()

    fun load(context: Context): Boolean = prefs(context).getBoolean(KEY_LOAD, false)
    fun setLoad(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_LOAD, on).apply()
}
