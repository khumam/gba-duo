package com.khumam.gbaduo.player

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.AchievementPopups
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.companion.CompanionActivity
import com.khumam.gbaduo.companion.CompanionController
import com.khumam.gbaduo.companion.CompanionPlacement
import com.khumam.gbaduo.companion.CompanionPresentation
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.emu.AudioOut
import com.khumam.gbaduo.emu.EmuThread
import com.khumam.gbaduo.emu.GameView
import com.khumam.gbaduo.emu.InputMapper
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.library.RomArchive
import com.khumam.gbaduo.library.RomHeader
import com.khumam.gbaduo.library.RomIndex
import com.khumam.gbaduo.storage.GameStorage
import com.khumam.gbaduo.storage.LegacyMigration
import java.io.File

class PlayerActivity : Activity() {

    private lateinit var input: InputMapper
    private lateinit var emu: EmuThread
    private lateinit var audioOut: AudioOut
    private var quickMenu: QuickMenu? = null
    private var companionScreen: CompanionController? = null
    private var companionPresentation: CompanionPresentation? = null
    private var companionActivity: CompanionActivity.Session? = null
    private var resumed = false
    private var achievementPopups: AchievementPopups? = null

    /**
     * Whether this is Android's top resumed activity: the one on the top focused display,
     * which is where controller key events go. Not the same as [hasWindowFocus], which is
     * per display: the game keeps window focus on its own screen even while key events
     * go to the other one.
     */
    private var topResumed = false

    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private var savedShader: ShaderPreset
        get() = ShaderPreset.byId(prefs.getString("shader_id", null))
        set(value) = prefs.edit().putString("shader_id", value.id).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        setContentView(R.layout.activity_player)
        // RetroAchievements: logs in with the stored token (if any) and shows popups over the game.
        RetroAchievements.init(this)
        achievementPopups = AchievementPopups(findViewById(android.R.id.content) as android.widget.FrameLayout).also { it.attach() }

        val romUriString = intent.getStringExtra(EXTRA_ROM_URI)
        val romFileName = intent.getStringExtra(EXTRA_ROM_FILE_NAME)
        if (romUriString == null || romFileName == null) {
            finish()
            return
        }

        // A zip is unpacked here: the core gets the ROM's own bytes either way.
        val rom = runCatching {
            contentResolver.openInputStream(Uri.parse(romUriString))?.use { RomArchive.readRom(romFileName, it) }
        }.getOrNull()
        if (rom == null) {
            Toast.makeText(this, getString(R.string.load_failed, romFileName), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val romBytes = rom.bytes

        // Everything about this game lives in one folder keyed by platform + ROM CRC.
        // mGBA's own CRC32 is over the same bytes, so this matches NativeCore.crc32().
        val header = RomHeader.parse(rom.fileName, romBytes.copyOf(minOf(romBytes.size, RomHeader.SIZE)))
        val crc32 = RomIndex.crc32Of(romBytes)
        val storage = GameStorage.forGame(filesDir, header.platform, header.title, header.gameCode, crc32)
        LegacyMigration.migrate(filesDir, romFileName, crc32, storage)
        storage.ensureDirs()

        input = InputMapper()
        audioOut = AudioOut()
        val gameView = findViewById<GameView>(R.id.game_view)
        gameView.setShader(savedShader)
        emu = EmuThread(romBytes, storage.saveFile.absolutePath, gameView, audioOut, input)
        emu.start()

        // The quick menu and companion screen talk to the core, so wait until the emu thread has loaded it.
        waitForLoadThenSetUp(gameView, header, crc32, storage)
    }

    private fun waitForLoadThenSetUp(gameView: GameView, header: RomHeader, crc32: Long, storage: GameStorage) {
        gameView.post(object : Runnable {
            override fun run() {
                if (!emu.isAlive) return
                if (emu.loaded) {
                    quickMenu = QuickMenu(
                        context = this@PlayerActivity,
                        emu = emu,
                        states = SaveStates(storage),
                        initialShader = savedShader,
                        onSwapAb = { input.swapAb = it },
                        onSetFfSpeed = { emu.ffMultiplier = it },
                        onSetShader = { savedShader = it; gameView.setShader(it) },
                        onQuit = { finish() },
                    )
                    setUpCompanion(header, crc32, storage)
                } else {
                    gameView.postDelayed(this, 16)
                }
            }
        })
    }

    /**
     * Shows the companion on the screen the game isn't on: a Presentation on the
     * second screen when the game is on the main one, or [CompanionActivity] on the
     * main screen when the game was opened on the second one.
     */
    private fun setUpCompanion(header: RomHeader, crc32: Long, storage: GameStorage) {
        val displayManager = getSystemService(DisplayManager::class.java)
        val gameDisplay = display?.displayId ?: CompanionPlacement.MAIN_DISPLAY
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }
        val controller = CompanionController(this, header.title, header.gameCode, crc32)
        when (val target = CompanionPlacement.choose(gameDisplay, presentationDisplays)) {
            is CompanionPlacement.Target.Presentation -> {
                val display = displayManager.getDisplay(target.displayId) ?: return
                companionPresentation = CompanionPresentation(this, display, controller, onKey = ::handleKeyEvent).also { it.show() }
            }
            is CompanionPlacement.Target.Activity -> {
                companionActivity = CompanionActivity.show(this, target.displayId, controller, onKey = ::handleKeyEvent, onBecameTop = ::keepFocusOnGame)
                    ?: return
            }
            CompanionPlacement.Target.None -> return
        }
        companionScreen = controller

        val packsDir = File(filesDir, "packs")
        val manifest = storage.packId?.let { PackStore(packsDir).byId(it) } ?: return
        val packDir = File(packsDir, manifest.id)
        if (!controller.showPack(manifest, packDir)) return

        emu.activePackDir = packDir
        emu.activePack = manifest
        emu.onSnapshot = { snapshot -> runOnUiThread { companionScreen?.update(snapshot) } }
        // Taps on the companion screen reach the pack's Lua through the emu thread.
        emu.onActionResult = { result -> runOnUiThread { companionScreen?.applyScriptResult(result) } }
        controller.onUiChange = emu::setUiState
        controller.onScriptCall = emu::callAction
        emu.setUiState(controller.uiState)
    }

    /**
     * Keeps the controller on the game. Android sends controller input to the focused
     * window, and focus can follow the "top" activity to the other screen: that happens
     * when [CompanionActivity] opens on the main screen, or is raised there again. The
     * companion's windows can't take focus themselves (and a [CompanionPresentation] is no
     * activity at all), so this is the one case to handle: each time, if the game is no
     * longer the top resumed activity a moment later, it moves itself back to the front
     * and takes the controller back.
     * Not while the quick menu is open (it has focus then) or the game is in the background.
     */
    private fun keepFocusOnGame() {
        val decor = window.decorView
        decor.removeCallbacks(reclaimFocus)
        decor.postDelayed(reclaimFocus, FOCUS_CHECK_DELAY_MS)
    }

    private val reclaimFocus = Runnable {
        if (resumed && !isFinishing && !topResumed && quickMenu?.isShowing != true) {
            // Restart this same instance on its own display: unlike moveTaskToFront (a no-op
            // when the task is already in front on its display), an activity start makes that
            // display the top focused one, and key events follow it there.
            val intent = Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display?.displayId ?: CompanionPlacement.MAIN_DISPLAY)
            startActivity(intent, options.toBundle())
        }
    }

    /** Shared by this activity and the companion screen, so the game gets the controller whichever window has focus. */
    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) quickMenu?.show()
            return true
        }
        if (!::input.isInitialized) return false
        return input.onKey(event.keyCode, down)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyUp(keyCode, event)

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A key released after focus moved elsewhere would never reach us and stay held.
        if (!hasFocus && ::input.isInitialized) input.releaseAll()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        if (::emu.isInitialized) emu.paused = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::emu.isInitialized) emu.paused = false
    }

    override fun onDestroy() {
        achievementPopups?.detach()
        if (::emu.isInitialized) {
            emu.shutdown()
            emu.join(1000)
        }
        if (::audioOut.isInitialized) audioOut.release()
        window.decorView.removeCallbacks(reclaimFocus)
        companionPresentation?.dismiss()
        companionActivity?.let(CompanionActivity::close)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROM_URI = "rom_uri"
        const val EXTRA_ROM_FILE_NAME = "rom_file_name"
        private const val FOCUS_CHECK_DELAY_MS = 400L
    }
}
