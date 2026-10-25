package com.khumam.gbaduo.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys
import java.io.File

/**
 * Library > Game settings: options that belong to one game, kept in its `settings.json`.
 * For now the shader (Off until picked); later options are one more row.
 */
class GameSettingsActivity : PixelPage() {

    private lateinit var settings: GameSettings
    private val summaries = mutableMapOf<ShaderPreset, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(EXTRA_GAME_DIR)
        if (dir == null) {
            finish()
            return
        }
        settings = GameSettings(File(dir, "settings.json"))
        page(getString(R.string.game_settings), listOf("▲ ▼" to "Choose", "A" to "Pick", "B" to "Back"))

        content.addView(
            hud(listOf(intent.getStringExtra(EXTRA_PLATFORM).orEmpty() to intent.getStringExtra(EXTRA_TITLE).orEmpty())),
            spaced(0, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(label(getString(R.string.game_settings_shader)), spaced(24))

        var first: android.view.View? = null
        for (preset in ShaderPreset.entries) {
            val (card, summary) = menuCard(R.drawable.ic_settings, Pixel.YELLOW, preset.displayName, description(preset)) { pick(preset) }
            summaries[preset] = summary
            if (first == null) first = card
        }
        render()
        focusFirst(first!!)
    }

    private fun description(preset: ShaderPreset): String = getString(
        when (preset) {
            ShaderPreset.OFF -> R.string.shader_off_desc
            ShaderPreset.GBA_COLOR -> R.string.shader_gba_color_desc
            ShaderPreset.LCD -> R.string.shader_lcd_desc
            ShaderPreset.SCANLINES -> R.string.shader_scanlines_desc
            ShaderPreset.SCALE2X -> R.string.shader_scale2x_desc
        },
    )

    private fun pick(preset: ShaderPreset) {
        settings.put(GameSettingsKeys.SHADER, preset.id)
        render()
    }

    /** The picked shader is marked with a check. */
    private fun render() {
        val picked = ShaderPreset.byId(settings.getString(GameSettingsKeys.SHADER))
        for ((preset, summary) in summaries) summary.text = (if (preset == picked) "✓ " else "") + description(preset)
    }

    companion object {
        const val EXTRA_GAME_DIR = "game_dir"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLATFORM = "platform"
    }
}
