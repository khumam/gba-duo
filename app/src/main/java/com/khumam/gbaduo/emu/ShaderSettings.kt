package com.khumam.gbaduo.emu

import android.content.Context
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys

/**
 * The shader every game uses unless its own Game settings pick one (Settings > Shader).
 * Off until the player chooses another.
 */
object ShaderSettings {
    private const val PREFS = "shader"
    private const val KEY_DEFAULT = "default"

    fun default(context: Context): ShaderPreset =
        ShaderPreset.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null))

    fun setDefault(context: Context, preset: ShaderPreset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, preset.id).apply()
    }

    /**
     * The game's own pick wins. A game set to Off (or never set) follows the app-wide
     * default, so a shader is off only when both are.
     */
    fun forGame(context: Context, settings: GameSettings): ShaderPreset =
        ShaderPreset.byId(settings.getString(GameSettingsKeys.SHADER)).takeIf { it != ShaderPreset.OFF } ?: default(context)
}
