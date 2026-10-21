package com.khumam.gbaduo.settings

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.library.ui.Pixel

/** The settings menu: ROM folders, RetroAchievements and About, as pixel cards. */
class SettingsActivity : PixelPage() {

    private lateinit var foldersSummary: TextView
    private lateinit var raSummary: TextView

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
    }

    override fun onStop() {
        RetroAchievements.removeStateListener(raListener)
        super.onStop()
    }
}
