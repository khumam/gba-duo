package com.khumam.gbaduo.emu

import android.content.Context

/**
 * Blur that grows toward the top and bottom of the game picture while the middle stays sharp, like a
 * tilt-shift photo (Settings > Tilt-shift blur). [maxRadius] is the blur's reach at the very edge, in screen
 * pixels for a picture 960 pixels tall (it scales with the picture); [focus] is how much of the picture,
 * measured from its middle, stays sharp (0 to 1).
 */
enum class TiltShift(val id: String, val displayName: String, val maxRadius: Float, val focus: Float) {
    OFF("off", "Off", 0f, 1f),
    LIGHT("light", "Light", 5f, 0.45f),
    STRONG("strong", "Strong", 12f, 0.35f),
    ;

    companion object {
        fun byId(id: String?): TiltShift = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The tilt-shift blur every game uses. Off until the player chooses one. */
object TiltShiftSettings {
    private const val PREFS = "tilt"
    private const val KEY_DEFAULT = "default"

    fun default(context: Context): TiltShift =
        TiltShift.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null))

    fun setDefault(context: Context, tilt: TiltShift) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, tilt.id).apply()
    }
}
