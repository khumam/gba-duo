package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.emu.ShaderPreset
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
 * The shader (Off until picked) and the cover artwork; later options are one more row.
 */
class GameSettingsActivity : PixelPage() {

    private lateinit var settings: GameSettings
    private lateinit var shaderSummary: TextView
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

    private fun current() = ShaderPreset.byId(settings.getString(GameSettingsKeys.SHADER))

    /** The row shows the picked shader. */
    private fun render() {
        val picked = current()
        shaderSummary.text = picked.displayName
    }

    /** A list of the shaders to choose from; picking one saves it for this game at once. */
    private fun chooseShader() {
        val presets = ShaderPreset.entries
        val labels = presets.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.game_settings_shader)
            .setSingleChoiceItems(labels, presets.indexOf(current())) { dialog, which ->
                settings.put(GameSettingsKeys.SHADER, presets[which].id)
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
