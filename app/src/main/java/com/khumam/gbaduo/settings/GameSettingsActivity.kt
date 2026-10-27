package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.emu.ColorCorrection
import com.khumam.gbaduo.emu.ColorSettings
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.ShaderSettings
import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.library.artwork.Artwork
import com.khumam.gbaduo.library.artwork.ArtworkPrefs
import com.khumam.gbaduo.library.artwork.Outcome
import com.khumam.gbaduo.library.artwork.ScrapeTarget
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys
import java.io.File
import java.util.concurrent.Executors

/**
 * Library > Game settings: options that belong to one game, kept in its `settings.json`.
 * The shader (Off follows the app-wide default) and the cover artwork; later options are one more row.
 */
class GameSettingsActivity : PixelPage() {

    private lateinit var settings: GameSettings
    private lateinit var shaderSummary: TextView
    private lateinit var colorSummary: TextView
    private lateinit var artworkState: TextView
    private val io = Executors.newSingleThreadExecutor()

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
        val (shaderCard, summary) = menuCard(R.drawable.ic_settings, Pixel.YELLOW, getString(R.string.game_settings_shader), "") { chooseShader() }
        shaderSummary = summary
        colorSummary = menuCard(R.drawable.ic_settings, Pixel.MINT, getString(R.string.color_title), "") { chooseColor() }.second
        render()

        content.addView(label(getString(R.string.game_settings_artwork)), spaced(24))
        artworkState = note("", top = 8)
        menuCard(R.drawable.ic_settings, Pixel.PINK, getString(R.string.artwork_find), getString(R.string.artwork_find_needs_key).takeIf { ArtworkPrefs.sgdbKey(this) == null } ?: getString(R.string.artwork_pick_hint)) { findOnline() }
        menuCard(R.drawable.ic_folders, Pixel.YELLOW, getString(R.string.artwork_match_folder), getString(R.string.artwork_match_needs_folder).takeIf { ArtworkPrefs.folderUri(this) == null } ?: getString(R.string.artwork_match_desc)) { matchFolder() }
        menuCard(R.drawable.ic_settings, Pixel.CORAL, getString(R.string.artwork_remove), getString(R.string.artwork_remove_desc)) { removeArtwork() }
        menuCard(R.drawable.ic_settings, Pixel.MINT, getString(R.string.artwork_auto), getString(R.string.artwork_auto_desc)) { backToAuto() }
        renderArtwork()
        focusFirst(shaderCard)
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) renderArtwork()
    }

    private val platformId get() = Platform.byId(intent.getStringExtra(EXTRA_PLATFORM_ID))
    private val crc get() = intent.getLongExtra(EXTRA_CRC, 0)

    private fun renderArtwork() {
        val platform = platformId ?: return
        val lock = settings.getString(GameSettingsKeys.ARTWORK)
        val meta = Artwork.store(this).meta(platform.id, crc)
        val text = when {
            lock == GameSettingsKeys.ARTWORK_NONE -> R.string.artwork_removed
            meta == null -> R.string.artwork_none
            lock == GameSettingsKeys.ARTWORK_PICKED -> R.string.artwork_picked
            meta.source == "folder" -> R.string.artwork_from_folder
            else -> R.string.artwork_from_sgdb
        }
        artworkState.text = getString(text)
    }

    private fun findOnline() {
        if (ArtworkPrefs.sgdbKey(this) == null) {
            Toast.makeText(this, R.string.artwork_find_needs_key, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ArtworkPickerActivity::class.java)
                .putExtra(EXTRA_GAME_DIR, intent.getStringExtra(EXTRA_GAME_DIR))
                .putExtra(EXTRA_TITLE, intent.getStringExtra(EXTRA_TITLE))
                .putExtra(EXTRA_PLATFORM_ID, intent.getStringExtra(EXTRA_PLATFORM_ID))
                .putExtra(EXTRA_CRC, crc),
        )
    }

    /** Looks for this one game in the artwork folder; the player asked, so a lock doesn't stop it. */
    private fun matchFolder() {
        val platform = platformId ?: return
        if (ArtworkPrefs.folderUri(this) == null) {
            Toast.makeText(this, R.string.artwork_match_needs_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val target = ScrapeTarget(
            platform, intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(), intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(EXTRA_CODE).orEmpty(), crc,
        )
        io.execute {
            val outcome = Artwork.scraper(this, useFolder = true, useWeb = false, respectLocks = false).scrapeOne(target, replace = true)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (outcome == Outcome.FOUND) {
                    settings.put(GameSettingsKeys.ARTWORK, null)
                    Toast.makeText(this, R.string.artwork_match_found, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.artwork_match_none, Toast.LENGTH_SHORT).show()
                }
                renderArtwork()
            }
        }
    }

    private fun removeArtwork() {
        val platform = platformId ?: return
        Artwork.store(this).remove(platform.id, crc)
        settings.put(GameSettingsKeys.ARTWORK, GameSettingsKeys.ARTWORK_NONE)
        renderArtwork()
    }

    private fun backToAuto() {
        settings.put(GameSettingsKeys.ARTWORK, null)
        renderArtwork()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** This game's own shader; Off (or none saved) means it follows the app-wide default. */
    private fun picked() = ShaderPreset.byId(settings.getString(GameSettingsKeys.SHADER))

    /** Off reads as "Default (X)", since an Off game uses the app-wide shader. */
    private fun label(preset: ShaderPreset) =
        if (preset == ShaderPreset.OFF) getString(R.string.shader_follow_default, ShaderSettings.default(this).displayName)
        else preset.displayName

    private fun render() {
        shaderSummary.text = label(picked())
        colorSummary.text = colorLabel(pickedColor())
    }

    private fun pickedColor() = ColorCorrection.byId(settings.getString(GameSettingsKeys.COLOR))

    private fun colorLabel(correction: ColorCorrection) =
        if (correction == ColorCorrection.OFF) getString(R.string.shader_follow_default, ColorSettings.default(this).displayName)
        else correction.displayName

    private fun chooseColor() {
        val options = ColorCorrection.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.color_title)
            .setSingleChoiceItems(options.map(::colorLabel).toTypedArray(), options.indexOf(pickedColor())) { dialog, which ->
                settings.put(GameSettingsKeys.COLOR, options[which].takeIf { it != ColorCorrection.OFF }?.id)
                render()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A list of the shaders to choose from; picking one saves it for this game at once. */
    private fun chooseShader() {
        val presets = ShaderPreset.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.game_settings_shader)
            .setSingleChoiceItems(presets.map(::label).toTypedArray(), presets.indexOf(picked())) { dialog, which ->
                settings.put(GameSettingsKeys.SHADER, presets[which].takeIf { it != ShaderPreset.OFF }?.id)
                render()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_GAME_DIR = "game_dir"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_PLATFORM_ID = "platform_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_CODE = "code"
        const val EXTRA_CRC = "crc"
    }
}
