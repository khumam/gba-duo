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

    private fun defaultId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null)

    /** The app-wide pick as saved (a library shader that has since been deleted still reads as itself here). */
    fun default(context: Context): ShaderChoice = ShaderChoice.parse(defaultId(context))

    fun setDefault(context: Context, choice: ShaderChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, choice.id).apply()
    }

    /**
     * The game's own pick wins. A game set to Off (or never set, or set to a library shader whose files are gone)
     * follows the app-wide default, so a shader is off only when both are.
     */
    fun inUse(context: Context, settings: GameSettings?): InUse =
        resolve(settings?.getString(GameSettingsKeys.SHADER), defaultId(context), ShaderLibrary.of(context))

    fun forGame(context: Context, settings: GameSettings): ShaderChoice = inUse(context, settings).choice

    /** The same rules as plain data, for tests. */
    fun resolve(gameId: String?, defaultId: String?, library: ShaderLibrary): InUse {
        var missing: ShaderChoice.Library? = null
        fun usable(choice: ShaderChoice): Boolean {
            if (choice == ShaderChoice.OFF) return false
            if (choice is ShaderChoice.Library && !library.isDownloaded(choice.path)) {
                if (missing == null) missing = choice
                return false
            }
            return true
        }
        val game = ShaderChoice.parse(gameId)
        if (usable(game)) return InUse(game, InUse.Source.GAME, missing)
        val fallback = ShaderChoice.parse(defaultId)
        if (usable(fallback)) return InUse(fallback, InUse.Source.DEFAULT, missing)
        return InUse(ShaderChoice.OFF, InUse.Source.NONE, missing)
    }
}
