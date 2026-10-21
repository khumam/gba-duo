package com.khumam.gbaduo.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelButton
import com.khumam.gbaduo.storage.DocumentSyncDir
import com.khumam.gbaduo.storage.SaveFolder
import com.khumam.gbaduo.storage.SaveLibrary
import java.io.File
import java.util.concurrent.Executors

/**
 * Settings > Save folder: pick a folder (SD card, a synced folder) that mirrors every
 * game's saves, change it, or stop using it. The first time, the whole library is
 * copied there and checked before the app switches to it.
 */
class SaveFolderActivity : PixelPage() {

    private lateinit var current: TextView
    private lateinit var status: TextView
    private lateinit var choose: PixelButton
    private lateinit var stop: PixelButton
    private val io = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.save_folder_title))
        note(getString(R.string.save_folder_intro), top = 0)

        current = hud(emptyList())
        content.addView(current, spaced(16, ViewGroup.LayoutParams.WRAP_CONTENT))

        choose = button(getString(R.string.save_folder_choose), Pixel.YELLOW) { pick() }
        content.addView(choose, spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))
        stop = button(getString(R.string.save_folder_stop), Pixel.WHITE_SOFT) { stopUsing() }
        content.addView(stop, spaced(14, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            typeface = Fonts.body(this@SaveFolderActivity, 600)
            textSize = 14f
            setTextColor(Pixel.ON_INK_SOFT)
            visibility = View.GONE
        }
        content.addView(status, spaced(14))

        content.addView(label(getString(R.string.save_folder_what_title)), spaced(28))
        note(getString(R.string.save_folder_what), top = 6)
        note(getString(R.string.save_folder_conflicts), top = 8)

        render()
        focusFirst(choose)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val name = SaveFolder.displayName(this)
        val set = SaveFolder.uri(this) != null
        current.text = hud(listOf(
            getString(R.string.save_folder_label) to when {
                !set -> getString(R.string.save_folder_none)
                name == null -> getString(R.string.save_folder_unreachable)
                else -> name
            },
        )).text
        choose.text = getString(if (set) R.string.save_folder_change else R.string.save_folder_choose)
        stop.visibility = if (set) View.VISIBLE else View.GONE
        choose.isEnabled = !busy
        stop.isEnabled = !busy
    }

    private fun showStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        status.visibility = View.VISIBLE
    }

    private fun pick() {
        if (busy) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK)
    }

    private fun stopUsing() {
        if (busy) return
        SaveFolder.clear(this)
        showStatus(getString(R.string.save_folder_stopped))
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri == SaveFolder.uri(this)) return
        runCatching { SaveFolder.takePermission(this, uri) }.onFailure {
            showStatus(getString(R.string.save_folder_no_access), error = true)
            return
        }
        move(uri)
    }

    /** Copies every game's saves into [uri] and checks them; switches only if all came out right. */
    private fun move(uri: Uri) {
        busy = true
        render()
        showStatus(getString(R.string.save_folder_copying))
        val gamesDir = File(filesDir, "games")
        io.execute {
            val root = DocumentSyncDir.open(this, uri)
            val result = root?.let { SaveLibrary.moveAll(gamesDir, it, uri.toString()) }
            runOnUiThread {
                busy = false
                when {
                    result == null -> {
                        SaveFolder.release(this, uri)
                        showStatus(getString(R.string.save_folder_no_access), error = true)
                    }
                    result.ok -> {
                        SaveFolder.use(this, uri)
                        showStatus(
                            if (result.games == 0) getString(R.string.save_folder_ready)
                            else resources.getQuantityString(R.plurals.save_folder_moved, result.games, result.games),
                        )
                    }
                    else -> {
                        // The old setting stays; what was copied stays in the folder for now.
                        SaveFolder.release(this, uri)
                        showStatus(getString(R.string.save_folder_move_failed, result.failed.joinToString(", ")), error = true)
                    }
                }
                render()
            }
        }
    }

    private companion object {
        const val REQUEST_PICK = 1
    }
}
