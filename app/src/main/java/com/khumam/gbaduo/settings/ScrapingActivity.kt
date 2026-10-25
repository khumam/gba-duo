package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.library.artwork.Artwork
import com.khumam.gbaduo.library.artwork.ArtworkPrefs
import com.khumam.gbaduo.library.artwork.Sgdb
import com.khumam.gbaduo.library.artwork.SteamGridDb
import com.khumam.gbaduo.library.artwork.Stop
import com.khumam.gbaduo.library.artwork.Summary
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelButton
import java.util.concurrent.Executors

/**
 * Settings > Scraping: where cover art comes from. An artwork folder another launcher
 * filled (read only), SteamGridDB with the player's own key, or both; then "Scrape library"
 * finds art for every game. Nothing goes online until the player presses a button here.
 */
class ScrapingActivity : PixelPage() {

    private lateinit var folderName: TextView
    private lateinit var stopFolder: PixelButton
    private lateinit var keyField: EditText
    private lateinit var keyStatus: TextView
    private lateinit var replaceButton: PixelButton
    private lateinit var runButton: PixelButton
    private lateinit var removeButton: PixelButton
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private var replace = false

    @Volatile private var cancelled = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.scraping_title))
        note(getString(R.string.scraping_intro), top = 0)

        content.addView(label(getString(R.string.scraping_folder_label)), spaced(24))
        folderName = hud(emptyList())
        content.addView(folderName, spaced(10, ViewGroup.LayoutParams.WRAP_CONTENT))
        val choose = button(getString(R.string.scraping_folder_choose), Pixel.YELLOW) { pickFolder() }
        content.addView(choose, spaced(14, ViewGroup.LayoutParams.WRAP_CONTENT))
        stopFolder = button(getString(R.string.scraping_folder_stop), Pixel.WHITE_SOFT) {
            ArtworkPrefs.clearFolder(this)
            renderFolder()
        }
        content.addView(stopFolder, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        note(getString(R.string.scraping_folder_note), top = 10)

        content.addView(label(getString(R.string.scraping_sgdb_label)), spaced(28))
        keyField = field(
            getString(R.string.scraping_sgdb_key),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            password = true,
        )
        keyField.setText(ArtworkPrefs.sgdbKey(this).orEmpty())
        content.addView(button(getString(R.string.scraping_test_key), Pixel.LAVENDER) { testKey() }, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        keyStatus = statusLine()
        content.addView(keyStatus, spaced(10))
        note(getString(R.string.scraping_sgdb_note), top = 8)

        content.addView(label(getString(R.string.scraping_run_label)), spaced(28))
        replaceButton = button("", Pixel.LAVENDER) { replace = !replace; renderReplace() }
        content.addView(replaceButton, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        runButton = button(getString(R.string.scraping_run), Pixel.YELLOW) { if (running) cancelled = true else scrape() }
        content.addView(runButton, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = statusLine()
        content.addView(status, spaced(12))
        removeButton = button(getString(R.string.scraping_remove_all), Pixel.CORAL) { confirmRemoveAll() }
        content.addView(removeButton, spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))

        renderFolder()
        renderReplace()
        focusFirst(choose)
    }

    private fun statusLine() = TextView(this).apply {
        typeface = Fonts.body(this@ScrapingActivity, 600)
        textSize = 14f
        setTextColor(Pixel.ON_INK_SOFT)
        visibility = View.GONE
    }

    private fun show(view: TextView, text: String, error: Boolean = false) {
        view.text = text
        view.setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        view.visibility = View.VISIBLE
    }

    private fun renderFolder() {
        val set = ArtworkPrefs.folderUri(this) != null
        folderName.text = hud(listOf(
            getString(R.string.scraping_folder_label) to
                if (set) ArtworkPrefs.folderName(this) ?: getString(R.string.save_folder_unreachable) else getString(R.string.scraping_folder_none),
        )).text
        stopFolder.visibility = if (set) View.VISIBLE else View.GONE
    }

    private fun renderReplace() {
        replaceButton.text = getString(R.string.scraping_replace, getString(if (replace) R.string.scraping_on else R.string.scraping_off))
    }

    private fun pickFolder() {
        if (running) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { ArtworkPrefs.useFolder(this, uri) }
        renderFolder()
    }

    private fun saveKey(): String? {
        val key = keyField.text.toString().trim()
        ArtworkPrefs.setSgdbKey(this, key)
        return key.takeIf { it.isNotEmpty() }
    }

    override fun onPause() {
        saveKey()
        super.onPause()
    }

    private fun testKey() {
        val key = saveKey() ?: return show(keyStatus, getString(R.string.scraping_key_missing), error = true)
        io.execute {
            val result = SteamGridDb(key, UrlHttp()).testKey()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Sgdb.Ok -> show(keyStatus, getString(R.string.scraping_key_ok))
                    is Sgdb.Failed -> show(keyStatus, reasonText(result.reason), error = true)
                }
            }
        }
    }

    private fun reasonText(reason: Sgdb.Reason) = getString(
        when (reason) {
            Sgdb.Reason.KEY_REJECTED -> R.string.scraping_key_rejected
            Sgdb.Reason.SLOW_DOWN -> R.string.scraping_slow_down
            else -> R.string.scraping_offline
        },
    )

    private fun scrape() {
        saveKey()
        val useFolder = ArtworkPrefs.folderUri(this) != null
        val useWeb = ArtworkPrefs.sgdbKey(this) != null
        if (!useFolder && !useWeb) return show(status, getString(R.string.scraping_nothing_set), error = true)
        setRunning(true)
        val replaceExisting = replace
        io.execute {
            val outcome = runCatching {
                val library = RomLibrary(this)
                val targets = library.list().map { Artwork.target(library, it) }
                if (targets.isEmpty()) null
                else Artwork.scraper(this, useFolder, useWeb).run(targets, replaceExisting, { cancelled }) { done, total ->
                    runOnUiThread { if (!isDestroyed) show(status, getString(R.string.scraping_progress, done, total)) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setRunning(false)
                outcome.onFailure { show(status, getString(R.string.scraping_offline), error = true) }
                    .onSuccess { summary ->
                        if (summary == null) show(status, getString(R.string.scraping_no_games), error = true)
                        else showSummary(summary)
                    }
            }
        }
    }

    private fun showSummary(s: Summary) {
        val done = getString(R.string.scraping_done, s.found, s.notFound, s.skipped) +
            if (s.failed > 0) ". " + getString(R.string.scraping_failed_extra, s.failed) else ""
        when (s.stopped) {
            Stop.NONE -> show(status, done)
            Stop.CANCELLED -> show(status, getString(R.string.scraping_stopped) + ". " + done)
            Stop.SLOW_DOWN -> show(status, getString(R.string.scraping_slow_down) + ". " + done, error = true)
            Stop.KEY_REJECTED -> show(status, getString(R.string.scraping_key_rejected) + ". " + done, error = true)
        }
    }

    private fun setRunning(value: Boolean) {
        running = value
        cancelled = false
        runButton.text = getString(if (value) R.string.scraping_cancel else R.string.scraping_run)
        removeButton.isEnabled = !value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun confirmRemoveAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.scraping_remove_all_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                Artwork.store(this).removeAll()
                show(status, getString(R.string.scraping_removed_all))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        cancelled = true
        io.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PICK = 1
    }
}
