package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.emu.ColorCorrection
import com.khumam.gbaduo.emu.ColorSettings
import com.khumam.gbaduo.emu.ShaderChoice
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.ShaderSettings
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys
import com.khumam.gbaduo.storage.SaveFolder
import com.khumam.gbaduo.storage.SaveImport
import com.khumam.gbaduo.storage.SaveLibrary
import java.io.File
import java.util.concurrent.Executors

/**
 * Library > Game settings: options that belong to one game, kept in its `settings.json`.
 * The shader (Off follows the app-wide default), the color correction, the cover (its options are on the Cover page) and
 * loading a save; later options are one more row.
 */
class GameSettingsActivity : PixelPage() {

    private lateinit var settings: GameSettings
    private lateinit var shaderSummary: TextView
    private lateinit var colorSummary: TextView
    private lateinit var coverSummary: TextView
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

        coverSummary = menuCard(R.drawable.ic_settings, Pixel.PINK, getString(R.string.game_settings_artwork), "") { openCover() }.second
        renderCover()

        content.addView(label(getString(R.string.game_settings_save)), spaced(24))
        menuCard(R.drawable.ic_save, Pixel.CORAL, getString(R.string.save_load), getString(R.string.save_load_desc)) { pickSave() }
        focusFirst(shaderCard)
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) {
            renderCover()   // the Cover page may have changed the cover
            render()        // the Shader page may have changed this game's shader
        }
    }

    private fun renderCover() {
        coverSummary.text = CoverActivity.state(this, settings, intent.getStringExtra(EXTRA_PLATFORM_ID), intent.getLongExtra(EXTRA_CRC, 0))
    }

    /** The cover options, on their own page (same extras). */
    private fun openCover() {
        startActivity(Intent(this, CoverActivity::class.java).putExtras(intent))
    }

    private fun pickSave() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_SAVE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        io.execute {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readNBytes(SaveImport.MAX_SIZE + 1) }
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bytes == null || !SaveImport.isValid(bytes)) {
                    Toast.makeText(this, R.string.save_load_invalid, Toast.LENGTH_LONG).show()
                } else {
                    confirmLoadSave(bytes)
                }
            }
        }
    }

    private fun confirmLoadSave(bytes: ByteArray) {
        val where = if (SaveFolder.uri(this) != null) R.string.save_load_confirm_folder else R.string.save_load_confirm_device
        AlertDialog.Builder(this)
            .setTitle(R.string.save_load)
            .setMessage(getString(R.string.save_load_confirm, getString(where)))
            .setPositiveButton(android.R.string.ok) { _, _ -> loadSave(bytes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadSave(bytes: ByteArray) {
        val gameDir = File(intent.getStringExtra(EXTRA_GAME_DIR) ?: return)
        io.execute {
            val folder: Uri? = SaveFolder.uri(this)
            val root = folder?.let { SaveFolder.open(this) }
            val remote = root?.let { SaveLibrary.gameDir(it, gameDir.parentFile?.name.orEmpty(), gameDir.name, create = true) }
            val result = SaveImport.import(gameDir, bytes, remote, folder?.toString().orEmpty())
            // A save folder is set but can't be reached: the device's copy is replaced and goes out later.
            val folderMissed = folder != null && remote == null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val message = when {
                    result is SaveImport.Result.Failed -> getString(R.string.save_load_failed, result.error)
                    result is SaveImport.Result.Done && (folderMissed || !result.synced) -> getString(R.string.save_load_done_unsynced)
                    else -> getString(R.string.save_load_done)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** This game's own shader; Off (or none saved) means it follows the app-wide default. */
    private fun picked() = ShaderChoice.parse(settings.getString(GameSettingsKeys.SHADER))

    /** Off reads as "Default (X)", since an Off game uses the app-wide shader. */
    private fun label(choice: ShaderChoice) =
        if (choice == ShaderChoice.OFF) getString(R.string.shader_follow_default, ShaderSettings.default(this).displayName)
        else choice.displayName

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

    /** This game's shader, on the Shader page; what it saves is read back when this page shows again. */
    private fun chooseShader() {
        startActivity(Intent(this, ShaderActivity::class.java).putExtra(ShaderActivity.EXTRA_GAME_DIR, intent.getStringExtra(EXTRA_GAME_DIR)))
    }

    companion object {
        private const val REQUEST_PICK_SAVE = 1
        const val EXTRA_GAME_DIR = "game_dir"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_PLATFORM_ID = "platform_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_CODE = "code"
        const val EXTRA_CRC = "crc"
    }
}
