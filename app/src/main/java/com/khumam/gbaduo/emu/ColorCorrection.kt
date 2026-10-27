package com.khumam.gbaduo.emu

import android.content.Context
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys

/**
 * Makes the picture look like the console's real screen. Applied in the shader, so it works
 * with every [ShaderPreset]; [mode] is the `ccMode` uniform (see `shaders/color_correction.glsl`).
 */
enum class ColorCorrection(val id: String, val displayName: String, val mode: Float) {
    OFF("off", "Off", 0f),
    GBA("gba", "GBA screen", 1f),
    GBC("gbc", "Game Boy Color screen", 2f),
    ;

    companion object {
        fun byId(id: String?): ColorCorrection = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * The color correction every game uses unless its own Game settings pick one (Settings > Color
 * correction). Off until the player chooses another.
 */
object ColorSettings {
    private const val PREFS = "color"
    private const val KEY_DEFAULT = "default"

    fun default(context: Context): ColorCorrection =
        ColorCorrection.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null))

    fun setDefault(context: Context, correction: ColorCorrection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, correction.id).apply()
    }

    /** Same rule as [ShaderSettings.forGame]: a game set to Off follows the app-wide default. */
    fun forGame(context: Context, settings: GameSettings): ColorCorrection =
        ColorCorrection.byId(settings.getString(GameSettingsKeys.COLOR)).takeIf { it != ColorCorrection.OFF } ?: default(context)
}
