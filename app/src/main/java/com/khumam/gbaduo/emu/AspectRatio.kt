package com.khumam.gbaduo.emu

import android.content.Context

/**
 * The shape of the game picture (Settings > Aspect ratio). [ratio] is width / height; null means
 * "the game's own" for [ORIGINAL]; the screen's own for [STRETCH], which fills it edge to edge.
 */
enum class AspectRatio(val id: String, val displayName: String, val ratio: Float?) {
    ORIGINAL("original", "Original", null),
    FOUR_THREE("4_3", "4:3", 4f / 3f),
    SIXTEEN_NINE("16_9", "16:9", 16f / 9f),
    STRETCH("stretch", "Stretch to screen", null),
    ;

    companion object {
        fun byId(id: String?): AspectRatio = entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/** The aspect ratio every game uses, on whichever screen it runs. Original until the player chooses another. */
object AspectSettings {
    private const val PREFS = "aspect"
    private const val KEY_DEFAULT = "default"

    fun default(context: Context): AspectRatio =
        AspectRatio.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null))

    fun setDefault(context: Context, aspect: AspectRatio) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, aspect.id).apply()
    }
}
