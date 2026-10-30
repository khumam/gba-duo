package com.khumam.gbaduo.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.khumam.gbaduo.BuildConfig
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.CatalogPack
import com.khumam.gbaduo.companion.CatalogStatus
import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.companion.PackCatalog
import com.khumam.gbaduo.companion.PackDownloader
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Pixel
import java.io.File
import java.util.concurrent.Executors

/**
 * Library > Companion pack > Get from the web: lists the catalog's packs for one game,
 * downloads the chosen one and installs it. Returns the installed pack's id so the
 * library can assign it to the game.
 */
class PackCatalogActivity : PixelPage() {

    private lateinit var gameCode: String
    private lateinit var gameTitle: String
    private lateinit var downloader: PackDownloader
    private lateinit var packStore: PackStore
    private lateinit var results: LinearLayout
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra(EXTRA_GAME_CODE)
        if (code == null) {
            finish()
            return
        }
        gameCode = code
        gameTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val packsDir = File(filesDir, "packs")
        packStore = PackStore(packsDir)
        downloader = PackDownloader(UrlHttp(), packsDir, cacheDir)

        page(getString(R.string.pack_web_title), listOf("▲ ▼" to "Choose", "A" to "Download", "B" to "Back"))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, spaced(8))
        load()
    }

    /** Shows [text] in place of the list; [retry] adds a Try again button. */
    private fun message(text: String, error: Boolean = true, retry: Boolean = false) {
        results.removeAllViews()
        results.addView(note(text, top = 8).also { content.removeView(it) }.apply {
            setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        })
        if (retry) {
            val again = button(getString(R.string.pack_web_retry), Pixel.YELLOW) { load() }
            results.addView(again, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
            focusFirst(again)
        }
    }

    private fun busy(text: String) {
        results.removeAllViews()
        results.addView(busyRow(text), spaced(12))
    }

    private fun failText(failed: Fetch.Failed): String = when (failed.reason) {
        Fetch.Reason.OFFLINE -> getString(R.string.pack_web_offline)
        Fetch.Reason.UNSUPPORTED_CATALOG -> getString(R.string.pack_web_unsupported)
        Fetch.Reason.DOWNLOAD_FAILED -> getString(R.string.pack_web_download_failed)
        Fetch.Reason.TOO_BIG -> getString(R.string.pack_web_too_big)
        Fetch.Reason.BAD_CHECKSUM -> getString(R.string.pack_web_bad_checksum)
        Fetch.Reason.INSTALL_FAILED -> getString(R.string.pack_import_failed, failed.detail.orEmpty())
    }

    private fun load() {
        busy(getString(R.string.pack_web_loading))
        io.execute {
            val result = downloader.catalog()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Fetch.Failed -> message(failText(result), retry = true)
                    is Fetch.Ok -> {
                        val packs = PackCatalog.forGame(result.value, gameCode)
                            .sortedWith(compareByDescending<CatalogPack> { it.official }.thenBy { it.title })
                        if (packs.isEmpty()) message(getString(R.string.pack_web_none, gameTitle.ifBlank { gameCode })) else showPacks(packs)
                    }
                }
            }
        }
    }

    private fun showPacks(packs: List<CatalogPack>) {
        results.removeAllViews()
        var first: View? = null
        for (pack in packs) {
            val status = PackCatalog.status(pack, packStore.byId(pack.id)?.version, BuildConfig.VERSION_NAME)
            val statusText = when (status) {
                CatalogStatus.NEW -> null
                CatalogStatus.INSTALLED -> getString(R.string.pack_web_installed)
                CatalogStatus.UPDATE -> getString(R.string.pack_web_update)
                CatalogStatus.NEEDS_NEWER_APP -> getString(R.string.pack_web_needs_app, pack.minAppVersion.orEmpty())
            }
            val meta = listOfNotNull(
                "v${pack.version}",
                pack.author.ifBlank { null },
                if (pack.canModifyGame) getString(R.string.pack_modifies_game) else null,
                statusText,
            ).joinToString(" · ")
            val (card, _) = menuCard(R.drawable.ic_settings, Pixel.PINK, pack.title, "$meta\n${pack.description}") {
                if (status == CatalogStatus.NEEDS_NEWER_APP) {
                    Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
                } else {
                    download(pack)
                }
            }
            content.removeView(card)
            results.addView(card, spaced(12))
            if (first == null) first = card
        }
        first?.requestFocus()
    }

    private fun download(pack: CatalogPack) {
        busy(getString(R.string.pack_web_downloading, pack.title))
        io.execute {
            val result = downloader.install(pack)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Fetch.Ok -> {
                        Toast.makeText(this, result.value.name, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_PACK_ID, result.value.id))
                        finish()
                    }
                    is Fetch.Failed -> message(failText(result), retry = true)
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_CODE = "game_code"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PACK_ID = "pack_id"
    }
}
