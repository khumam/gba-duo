package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.library.artwork.Artwork
import com.khumam.gbaduo.library.artwork.ArtworkPrefs
import com.khumam.gbaduo.library.artwork.Stop
import com.khumam.gbaduo.library.artwork.Summary
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelButton
import java.util.concurrent.Executors

/**
 * Settings > Scraping: find a cover for every game. Where covers come from is set up under
 * Providers; this page runs the scrape and clears covers. Nothing goes online until the
 * player presses a button.
 */
class ScrapingActivity : PixelPage() {

    private lateinit var providersSummary: TextView
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

        val (providers, summary) = menuCard(R.drawable.ic_folders, Pixel.YELLOW,
            getString(R.string.providers_title), "") { startActivity(Intent(this, ProvidersActivity::class.java)) }
        providersSummary = summary

        content.addView(label(getString(R.string.scraping_run_label)), spaced(28))
        replaceButton = button("", Pixel.LAVENDER) { replace = !replace; renderReplace() }
        content.addView(replaceButton, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        runButton = button(getString(R.string.scraping_run), Pixel.YELLOW) { if (running) cancelled = true else scrape() }
        content.addView(runButton, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        note(getString(R.string.scraping_run_note), top = 10)
        status = statusLine()
        content.addView(status, spaced(12))
        removeButton = button(getString(R.string.scraping_remove_all), Pixel.CORAL) { confirmRemoveAll() }
        content.addView(removeButton, spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))

        renderReplace()
        focusFirst(providers)
    }

    override fun onStart() {
        super.onStart()
        providersSummary.text = Providers.summary(this)
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

    private fun renderReplace() {
        replaceButton.text = getString(R.string.scraping_replace, getString(if (replace) R.string.scraping_on else R.string.scraping_off))
    }

    private fun scrape() {
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
        fun sentence(text: String) = if (text.endsWith(".")) text else "$text."
        val result = sentence(getString(R.string.scraping_done, s.found, s.notFound, s.skipped)) +
            if (s.failed > 0) " " + sentence(getString(R.string.scraping_failed_extra, s.failed)) else ""
        val why = when (s.stopped) {
            Stop.NONE -> null
            Stop.CANCELLED -> R.string.scraping_stopped
            Stop.SLOW_DOWN -> R.string.scraping_slow_down
            Stop.KEY_REJECTED -> R.string.scraping_key_rejected
        }
        show(status, if (why == null) result else sentence(getString(why)) + " " + result, error = s.stopped == Stop.SLOW_DOWN || s.stopped == Stop.KEY_REJECTED)
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

}
