package com.khumam.gbaduo.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.CompanionPresentation
import com.khumam.gbaduo.companion.PackAssignments
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.emu.AudioOut
import com.khumam.gbaduo.emu.EmuThread
import com.khumam.gbaduo.emu.GameView
import com.khumam.gbaduo.emu.InputMapper
import com.khumam.gbaduo.emu.NativeCore
import com.khumam.gbaduo.emu.ShaderPreset
import java.io.File

class PlayerActivity : Activity() {

    private lateinit var input: InputMapper
    private lateinit var emu: EmuThread
    private lateinit var audioOut: AudioOut
    private var quickMenu: QuickMenu? = null
    private var companionScreen: CompanionPresentation? = null

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
        val saveName = intent.getStringExtra(EXTRA_SAVE_NAME)
        if (romUriString == null || saveName == null) {
            finish()
            return
        }

        val romBytes = contentResolver.openInputStream(Uri.parse(romUriString))?.use { it.readBytes() }
        if (romBytes == null) {
            Toast.makeText(this, getString(R.string.load_failed, saveName), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val saveDir = File(filesDir, "saves").apply { mkdirs() }
        val savePath = File(saveDir, saveName).absolutePath

        input = InputMapper()
        audioOut = AudioOut()
        val gameView = findViewById<GameView>(R.id.game_view)
        gameView.setShader(savedShader)
        emu = EmuThread(romBytes, savePath, gameView, audioOut, input)
        emu.start()

        // crc32() is only valid once the core has finished loading on the emu thread;
        // poll briefly on the UI thread before building the quick menu (needs it for save-state paths).
        waitForLoadThenBuildQuickMenu(gameView, saveName)
    }

    private fun waitForLoadThenBuildQuickMenu(gameView: GameView, saveName: String) {
        gameView.post(object : Runnable {
            override fun run() {
                if (!emu.isAlive) return
                if (emu.loaded) {
                    quickMenu = QuickMenu(
                        context = this@PlayerActivity,
                        emu = emu,
                        states = SaveStates(this@PlayerActivity, crc32 = NativeCore.crc32()),
                        initialShader = savedShader,
                        onSwapAb = { input.swapAb = it },
                        onSetFfSpeed = { emu.ffMultiplier = it },
                        onSetShader = { savedShader = it; gameView.setShader(it) },
                        onQuit = { finish() },
                    )
                    setUpCompanion(saveName)
                } else {
                    gameView.postDelayed(this, 16)
                }
            }
        })
    }

    private fun setUpCompanion(saveName: String) {
        val romFileName = saveName.removeSuffix(".sav") + ".gba"
        val packsDir = File(filesDir, "packs")
        val assignments = PackAssignments(File(filesDir, "pack_assignments.json"))
        val packId = assignments.get(romFileName) ?: return
        val manifest = PackStore(packsDir).byId(packId) ?: return

        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() ?: return

        val packDir = File(packsDir, manifest.id)
        val presentation = CompanionPresentation(this, display, manifest.name, NativeCore.gameCode(), NativeCore.crc32())
        presentation.show()
        presentation.showPack(manifest, packDir)
        companionScreen = presentation

        emu.activePackDir = packDir
        emu.activePack = manifest
        emu.onSnapshot = { snapshot -> runOnUiThread { companionScreen?.update(snapshot) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BACK) {
            quickMenu?.show()
            return true
        }
        if (input.onKey(keyCode, down = true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (input.onKey(keyCode, down = false)) return true
        return super.onKeyUp(keyCode, event)
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
        companionScreen?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROM_URI = "rom_uri"
        const val EXTRA_SAVE_NAME = "save_name"
    }
}
