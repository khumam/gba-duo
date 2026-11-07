package com.khumam.gbaduo.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.khumam.gbaduo.R
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
 * Library > Game settings > Cover: where one game's cover comes from. Opened with the same extras as
 * [GameSettingsActivity]; the lock it keeps is in the game's `settings.json`.
 */
class CoverActivity : PixelPage() {

    private lateinit var settings: GameSettings
    private lateinit var artworkState: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(GameSettingsActivity.EXTRA_GAME_DIR)
        if (dir == null) {
            finish()
            return
        }
        settings = GameSettings(File(dir, "settings.json"))
        page(getString(R.string.game_settings_artwork), listOf("▲ ▼" to "Choose", "A" to "Pick", "B" to "Back"))

        content.addView(
            hud(listOf(intent.getStringExtra(GameSettingsActivity.EXTRA_PLATFORM).orEmpty() to intent.getStringExtra(GameSettingsActivity.EXTRA_TITLE).orEmpty())),
            spaced(0, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        artworkState = note("", top = 8)
        val (first, _) = menuCard(R.drawable.ic_settings, Pixel.PINK, getString(R.string.artwork_find), getString(R.string.artwork_find_needs_key).takeIf { ArtworkPrefs.sgdbKey(this) == null } ?: getString(R.string.artwork_pick_hint)) { findOnline() }
        menuCard(R.drawable.ic_folders, Pixel.YELLOW, getString(R.string.artwork_match_folder), getString(R.string.artwork_match_needs_folder).takeIf { ArtworkPrefs.folderUri(this) == null } ?: getString(R.string.artwork_match_desc)) { matchFolder() }
        menuCard(R.drawable.ic_settings, Pixel.CORAL, getString(R.string.artwork_remove), getString(R.string.artwork_remove_desc)) { removeArtwork() }
        menuCard(R.drawable.ic_settings, Pixel.MINT, getString(R.string.artwork_auto), getString(R.string.artwork_auto_desc)) { backToAuto() }
        renderArtwork()
        focusFirst(first)
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) renderArtwork()   // the picker may have chosen a cover
    }

    private val platformId get() = Platform.byId(intent.getStringExtra(GameSettingsActivity.EXTRA_PLATFORM_ID))
    private val crc get() = intent.getLongExtra(GameSettingsActivity.EXTRA_CRC, 0)

    private fun renderArtwork() {
        artworkState.text = state(this, settings, intent.getStringExtra(GameSettingsActivity.EXTRA_PLATFORM_ID), crc)
    }

    private fun findOnline() {
        if (ArtworkPrefs.sgdbKey(this) == null) {
            Toast.makeText(this, R.string.artwork_find_needs_key, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ArtworkPickerActivity::class.java)
                .putExtra(GameSettingsActivity.EXTRA_GAME_DIR, intent.getStringExtra(GameSettingsActivity.EXTRA_GAME_DIR))
                .putExtra(GameSettingsActivity.EXTRA_TITLE, intent.getStringExtra(GameSettingsActivity.EXTRA_TITLE))
                .putExtra(GameSettingsActivity.EXTRA_PLATFORM_ID, intent.getStringExtra(GameSettingsActivity.EXTRA_PLATFORM_ID))
                .putExtra(GameSettingsActivity.EXTRA_CRC, crc),
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
            platform, intent.getStringExtra(GameSettingsActivity.EXTRA_FILE_NAME).orEmpty(), intent.getStringExtra(GameSettingsActivity.EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(GameSettingsActivity.EXTRA_CODE).orEmpty(), crc,
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

    companion object {
        /** Where this game's cover comes from, in words: the Cover page's note and Game settings' summary. */
        fun state(context: Context, settings: GameSettings, platformId: String?, crc: Long): String {
            val platform = Platform.byId(platformId) ?: return ""
            val lock = settings.getString(GameSettingsKeys.ARTWORK)
            val meta = Artwork.store(context).meta(platform.id, crc)
            val text = when {
                lock == GameSettingsKeys.ARTWORK_NONE -> R.string.artwork_removed
                meta == null -> R.string.artwork_none
                lock == GameSettingsKeys.ARTWORK_PICKED -> R.string.artwork_picked
                meta.source == "folder" -> R.string.artwork_from_folder
                else -> R.string.artwork_from_sgdb
            }
            return context.getString(text)
        }
    }
}
