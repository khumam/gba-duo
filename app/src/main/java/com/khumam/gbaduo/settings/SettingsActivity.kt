package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.companion.CompanionSettings
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.storage.SaveFolder

/** The settings menu: ROM folders, save folder, pack writes, RetroAchievements and About, as pixel cards. */
class SettingsActivity : PixelPage() {

    private lateinit var foldersSummary: TextView
    private lateinit var raSummary: TextView
    private lateinit var savesSummary: TextView
    private lateinit var writesSummary: TextView

    private val raListener: (RetroAchievements.State) -> Unit = { state ->
        raSummary.text = when (state) {
            is RetroAchievements.State.LoggedIn -> getString(R.string.ra_logged_in_as, state.displayName, state.score)
            RetroAchievements.State.LoggingIn -> getString(R.string.ra_logging_in)
            else -> getString(R.string.ra_logged_out)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroAchievements.init(this)
        page(getString(R.string.settings_title))

        val (folders, foldersText) = menuCard(R.drawable.ic_folders, Pixel.YELLOW,
            getString(R.string.manage_folders), getString(R.string.rom_folders_summary)) {
            startActivity(Intent(this, RomFoldersActivity::class.java))
        }
        foldersSummary = foldersText
        savesSummary = menuCard(R.drawable.ic_save, Pixel.CORAL,
            getString(R.string.save_folder_title), getString(R.string.save_folder_summary_none)) {
            startActivity(Intent(this, SaveFolderActivity::class.java))
        }.second
        writesSummary = menuCard(R.drawable.ic_settings, Pixel.PURPLE_LIGHT,
            getString(R.string.pack_writes_title), getString(R.string.pack_writes_off)) { toggleWrites() }.second
        raSummary = menuCard(R.drawable.ic_trophy, Pixel.MINT,
            getString(R.string.ra_title), getString(R.string.ra_logged_out)) {
            startActivity(Intent(this, RetroAchievementsActivity::class.java))
        }.second
        menuCard(R.drawable.ic_info, Pixel.PINK, getString(R.string.about_title), getString(R.string.about_tagline)) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        focusFirst(folders)
    }

    override fun onStart() {
        super.onStart()
        RetroAchievements.addStateListener(raListener)
        val count = RomLibrary(this).folders.size
        foldersSummary.text = if (count == 0) getString(R.string.no_folders_yet) else getString(R.string.rom_folders_summary)
        renderWrites()
        val saveFolder = if (SaveFolder.uri(this) != null) SaveFolder.displayName(this) ?: getString(R.string.save_folder_unreachable) else null
        savesSummary.text = saveFolder?.let { getString(R.string.save_folder_summary_set, it) } ?: getString(R.string.save_folder_summary_none)
    }

    private fun renderWrites() {
        writesSummary.text = getString(if (CompanionSettings.allowWrites(this)) R.string.pack_writes_on else R.string.pack_writes_off)
    }

    /** Off -> on asks first; on -> off is immediate. */
    private fun toggleWrites() {
        if (CompanionSettings.allowWrites(this)) {
            CompanionSettings.setAllowWrites(this, false)
            renderWrites()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pack_writes_confirm_title)
            .setMessage(R.string.pack_writes_confirm)
            .setPositiveButton(R.string.pack_writes_turn_on) { _, _ ->
                CompanionSettings.setAllowWrites(this, true)
                renderWrites()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStop() {
        RetroAchievements.removeStateListener(raListener)
        super.onStop()
    }
}
