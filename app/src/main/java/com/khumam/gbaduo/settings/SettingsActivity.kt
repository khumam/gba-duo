package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.HardcoreMode
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.companion.CompanionMode
import com.khumam.gbaduo.companion.CompanionSettings
import com.khumam.gbaduo.emu.AspectRatio
import com.khumam.gbaduo.emu.AspectSettings
import com.khumam.gbaduo.emu.ColorCorrection
import com.khumam.gbaduo.emu.ColorSettings
import com.khumam.gbaduo.emu.PerfOverlay
import com.khumam.gbaduo.emu.PerfOverlaySettings
import com.khumam.gbaduo.emu.TiltShift
import com.khumam.gbaduo.emu.TiltShiftSettings
import com.khumam.gbaduo.emu.ScaleSettings
import com.khumam.gbaduo.emu.ShaderChoice
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.ShaderSettings
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.player.AutoState
import com.khumam.gbaduo.player.PixelMenuDialog
import com.khumam.gbaduo.library.artwork.Artwork
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.storage.SaveFolder

/** The settings menu: ROM folders, save folder, pack writes, RetroAchievements and About, as pixel cards. */
class SettingsActivity : PixelPage() {

    private lateinit var foldersSummary: TextView
    private lateinit var raSummary: TextView
    private lateinit var savesSummary: TextView
    private lateinit var writesSummary: TextView
    private lateinit var autoSaveSummary: TextView
    private lateinit var autoLoadSummary: TextView
    private lateinit var scrapingSummary: TextView
    private lateinit var shaderSummary: TextView
    private lateinit var colorSummary: TextView
    private lateinit var aspectSummary: TextView
    private lateinit var tiltSummary: TextView
    private lateinit var scaleSummary: TextView
    private lateinit var perfSummary: TextView
    private lateinit var companionSummary: TextView

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

        // Games: where they come from and how they look on the shelf.
        group(R.string.settings_group_games, first = true)
        val (folders, foldersText) = menuCard(R.drawable.ic_folders, Pixel.YELLOW,
            getString(R.string.manage_folders), getString(R.string.rom_folders_summary)) {
            startActivity(Intent(this, RomFoldersActivity::class.java))
        }
        foldersSummary = foldersText
        scrapingSummary = menuCard(R.drawable.ic_settings, Pixel.PINK,
            getString(R.string.scraping_title), getString(R.string.scraping_summary)) {
            startActivity(Intent(this, ScrapingActivity::class.java))
        }.second

        group(R.string.settings_group_saves)
        savesSummary = menuCard(R.drawable.ic_save, Pixel.CORAL,
            getString(R.string.save_folder_title), getString(R.string.save_folder_summary_none)) {
            startActivity(Intent(this, SaveFolderActivity::class.java))
        }.second
        autoSaveSummary = menuCard(R.drawable.ic_save, Pixel.MINT,
            getString(R.string.auto_save_title), getString(R.string.auto_save_off)) { chooseAutoSave() }.second
        autoLoadSummary = menuCard(R.drawable.ic_save, Pixel.YELLOW,
            getString(R.string.auto_load_title), getString(R.string.auto_load_off)) { chooseAutoLoad() }.second

        group(R.string.settings_group_controls)
        menuCard(R.drawable.ic_settings, Pixel.MINT,
            getString(R.string.controls_title), getString(R.string.controls_summary)) {
            startActivity(Intent(this, ControllerActivity::class.java))
        }

        group(R.string.settings_group_picture)
        shaderSummary = menuCard(R.drawable.ic_settings, Pixel.YELLOW,
            getString(R.string.shader_title), getString(R.string.shader_summary)) { chooseShader() }.second
        colorSummary = menuCard(R.drawable.ic_settings, Pixel.MINT,
            getString(R.string.color_title), getString(R.string.color_summary)) { chooseColor() }.second
        aspectSummary = menuCard(R.drawable.ic_settings, Pixel.CORAL,
            getString(R.string.aspect_title), getString(R.string.aspect_summary)) { chooseAspect() }.second
        tiltSummary = menuCard(R.drawable.ic_settings, Pixel.YELLOW,
            getString(R.string.tilt_title), getString(R.string.tilt_summary)) { chooseTilt() }.second
        scaleSummary = menuCard(R.drawable.ic_settings, Pixel.PURPLE_LIGHT,
            getString(R.string.scale_title), getString(R.string.scale_off)) { chooseScale() }.second
        perfSummary = menuCard(R.drawable.ic_settings, Pixel.MINT,
            getString(R.string.perf_title), getString(R.string.perf_summary)) { choosePerf() }.second

        group(R.string.settings_group_companion)
        companionSummary = menuCard(R.drawable.ic_settings, Pixel.PURPLE_LIGHT,
            getString(R.string.companion_title), getString(R.string.companion_summary)) { chooseCompanion() }.second
        writesSummary = menuCard(R.drawable.ic_settings, Pixel.PURPLE_LIGHT,
            getString(R.string.pack_writes_title), getString(R.string.pack_writes_off)) { chooseWrites() }.second

        group(R.string.settings_group_more)
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
        renderAutoState()
        shaderSummary.text = getString(R.string.shader_summary_current, ShaderSettings.default(this).displayName)
        colorSummary.text = getString(R.string.shader_summary_current, ColorSettings.default(this).displayName)
        aspectSummary.text = getString(R.string.shader_summary_current, AspectSettings.default(this).displayName)
        tiltSummary.text = getString(R.string.shader_summary_current, TiltShiftSettings.default(this).displayName)
        perfSummary.text = getString(R.string.shader_summary_current, PerfOverlaySettings.default(this).displayName)
        renderScale()
        companionSummary.text = getString(companionTitle(CompanionSettings.mode(this)))
        val artCount = Artwork.store(this).count()
        scrapingSummary.text = if (artCount == 0) getString(R.string.scraping_summary)
        else resources.getQuantityString(R.plurals.scraping_summary_count, artCount, artCount)
        val saveFolder = if (SaveFolder.uri(this) != null) SaveFolder.displayName(this) ?: getString(R.string.save_folder_unreachable) else null
        savesSummary.text = saveFolder?.let { getString(R.string.save_folder_summary_set, it) } ?: getString(R.string.save_folder_summary_none)
    }

    /** The app-wide shader (the Shader page); a game can still pick its own in Game settings. */
    private fun chooseShader() {
        startActivity(Intent(this, ShaderActivity::class.java))
    }

    /** A section heading above the cards that follow it. */
    private fun group(title: Int, first: Boolean = false) {
        content.addView(label(getString(title)), spaced(if (first) 0 else 24))
    }

    /**
     * The settings picker: the in-game menu's panel, the current choice ticked and under the cursor.
     * Picking saves it; B or Back leaves it as it was.
     */
    private fun <T> pick(title: Int, options: List<T>, current: T, name: (T) -> String, onPick: (T) -> Unit) {
        val rows = options.map { option ->
            val label = name(option)
            (if (option == current) getString(R.string.companion_selected, label) else label) to { onPick(option) }
        }
        PixelMenuDialog(this, getString(title), rows, focusIndex = options.indexOf(current).coerceAtLeast(0)).show()
    }

    /** The app-wide color correction; a game can still pick its own in Game settings. */
    private fun chooseColor() = pick(R.string.color_title, ColorCorrection.entries, ColorSettings.default(this), { it.displayName }) {
        ColorSettings.setDefault(this, it)
        colorSummary.text = getString(R.string.shader_summary_current, it.displayName)
    }

    /** Blur toward the top and bottom of the game picture; the next game started uses it. */
    private fun chooseTilt() = pick(R.string.tilt_title, TiltShift.entries, TiltShiftSettings.default(this), { it.displayName }) {
        TiltShiftSettings.setDefault(this, it)
        tiltSummary.text = getString(R.string.shader_summary_current, it.displayName)
    }

    /** FPS and CPU readout over the game; the next game started uses it. */
    private fun choosePerf() = pick(R.string.perf_title, PerfOverlay.entries, PerfOverlaySettings.default(this), { it.displayName }) {
        PerfOverlaySettings.setDefault(this, it)
        perfSummary.text = getString(R.string.shader_summary_current, it.displayName)
    }

    /** The shape of the game picture, on whichever screen the game runs. */
    private fun chooseAspect() = pick(R.string.aspect_title, AspectRatio.entries, AspectSettings.default(this), { it.displayName }) {
        AspectSettings.setDefault(this, it)
        aspectSummary.text = getString(R.string.shader_summary_current, it.displayName)
    }

    private fun renderScale() {
        scaleSummary.text = getString(if (ScaleSettings.integer(this)) R.string.scale_on else R.string.scale_off)
    }

    /** On or off; the next game started uses it. */
    private fun chooseScale() = pick(R.string.scale_title, listOf(true, false), ScaleSettings.integer(this),
        { getString(if (it) R.string.scale_on else R.string.scale_off) }) {
        ScaleSettings.setInteger(this, it)
        renderScale()
    }

    /** What the second screen shows while a game runs; the next game started uses it. */
    private fun chooseCompanion() = pick(R.string.companion_title, listOf(CompanionMode.ACTIVE, CompanionMode.BLANK_ART, CompanionMode.OFF),
        CompanionSettings.mode(this), { getString(R.string.picker_option_detail, getString(companionTitle(it)), getString(companionSummary(it))) }) {
        CompanionSettings.setMode(this, it)
        companionSummary.text = getString(companionTitle(it))
    }

    private fun companionTitle(mode: CompanionMode) = when (mode) {
        CompanionMode.OFF -> R.string.companion_off
        CompanionMode.BLANK_ART -> R.string.companion_blank
        CompanionMode.ACTIVE -> R.string.companion_active
    }

    private fun companionSummary(mode: CompanionMode) = when (mode) {
        CompanionMode.OFF -> R.string.companion_off_summary
        CompanionMode.BLANK_ART -> R.string.companion_blank_summary
        CompanionMode.ACTIVE -> R.string.companion_active_summary
    }

    private fun renderAutoState() {
        autoSaveSummary.text = getString(if (AutoState.save(this)) R.string.auto_save_on else R.string.auto_save_off)
        autoLoadSummary.text = getString(if (AutoState.load(this)) R.string.auto_load_on else R.string.auto_load_off)
    }

    private fun chooseAutoSave() = pick(R.string.auto_save_title, listOf(true, false), AutoState.save(this),
        { getString(if (it) R.string.auto_save_on else R.string.auto_save_off) }) {
        AutoState.setSave(this, it)
        renderAutoState()
    }

    private fun chooseAutoLoad() = pick(R.string.auto_load_title, listOf(true, false), AutoState.load(this),
        { getString(if (it) R.string.auto_load_on else R.string.auto_load_off) }) {
        AutoState.setLoad(this, it)
        renderAutoState()
    }

    private fun renderWrites() {
        writesSummary.text = getString(when {
            HardcoreMode.enabled(this) -> R.string.pack_writes_hardcore
            CompanionSettings.allowWrites(this) -> R.string.pack_writes_on
            else -> R.string.pack_writes_off
        })
    }

    /** On or off; turning it on still asks first, turning it off is immediate. */
    private fun chooseWrites() {
        if (HardcoreMode.enabled(this)) {
            Toast.makeText(this, R.string.pack_writes_hardcore, Toast.LENGTH_SHORT).show()
            return
        }
        pick(R.string.pack_writes_title, listOf(true, false), CompanionSettings.allowWrites(this),
            { getString(if (it) R.string.pack_writes_on else R.string.pack_writes_off) }) { on ->
            when {
                !on -> { CompanionSettings.setAllowWrites(this, false); renderWrites() }
                !CompanionSettings.allowWrites(this) -> confirmWrites()
            }
        }
    }

    private fun confirmWrites() {
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
