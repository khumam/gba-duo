package com.khumam.gbaduo.player

import android.app.Activity
import android.app.ActivityManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import com.khumam.gbaduo.R
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

        val romUriString = intent.getStringExtra(EXTRA_ROM_URI)
        val romFileName = intent.getStringExtra(EXTRA_ROM_FILE_NAME)
        if (romUriString == null || romFileName == null) {
            finish()
            return
        }

        val romBytes = runCatching {
            contentResolver.openInputStream(Uri.parse(romUriString))?.use { it.readBytes() }
        }.getOrNull()
        if (romBytes == null) {
            Toast.makeText(this, getString(R.string.load_failed, romFileName), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Everything about this game lives in one folder keyed by platform + ROM CRC.
        // mGBA's own CRC32 is over the same bytes, so this matches NativeCore.crc32().
        val header = RomHeader.parse(romFileName, romBytes.copyOf(minOf(romBytes.size, RomHeader.SIZE)))
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
                companionActivity = CompanionActivity.show(this, target.displayId, controller, onKey = ::handleKeyEvent, onShown = ::keepFocusOnGame)
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
     * Starting [CompanionActivity] on the main screen can briefly hand that screen the
     * input focus (its own window can't take focus, but what's behind it might). If the
     * game lost focus that way, bring it back so the controller keeps driving the game.
     */
    private fun keepFocusOnGame() {
        window.decorView.postDelayed({
            if (!isFinishing && !hasWindowFocus()) {
                getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
            }
        }, FOCUS_CHECK_DELAY_MS)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A key released after focus moved elsewhere would never reach us and stay held.
        if (!hasFocus && ::input.isInitialized) input.releaseAll()
    }

    override fun onPause() {
        super.onPause()
        if (::emu.isInitialized) emu.paused = true
    }

    override fun onResume() {
        super.onResume()
        if (::emu.isInitialized) emu.paused = false
    }

    override fun onDestroy() {
        if (::emu.isInitialized) {
            emu.shutdown()
            emu.join(1000)
        }
        if (::audioOut.isInitialized) audioOut.release()
        companionPresentation?.dismiss()
        companionActivity?.let(CompanionActivity::close)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROM_URI = "rom_uri"
        const val EXTRA_ROM_FILE_NAME = "rom_file_name"
        private const val FOCUS_CHECK_DELAY_MS = 300L
    }
}
