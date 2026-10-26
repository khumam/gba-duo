package com.khumam.gbaduo.settings

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.library.artwork.Artwork
import com.khumam.gbaduo.library.artwork.ArtworkImages
import com.khumam.gbaduo.library.artwork.ArtworkPrefs
import com.khumam.gbaduo.library.artwork.Sgdb
import com.khumam.gbaduo.library.artwork.SgdbGame
import com.khumam.gbaduo.library.artwork.SgdbGrid
import com.khumam.gbaduo.library.artwork.SteamGridDb
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys
import java.io.File
import java.util.concurrent.Executors

/**
 * Game settings > Find on SteamGridDB: search by name, pick the game, then pick the cover
 * from its thumbnails. The pick is kept as "picked by you", so scraping never replaces it.
 */
class ArtworkPickerActivity : PixelPage() {

    private lateinit var gameDir: String
    private lateinit var platform: Platform
    private var crc = 0L
    private lateinit var api: SteamGridDb
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private val io = Executors.newFixedThreadPool(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(GameSettingsActivity.EXTRA_GAME_DIR)
        val key = ArtworkPrefs.sgdbKey(this)
        val platformId = Platform.byId(intent.getStringExtra(GameSettingsActivity.EXTRA_PLATFORM_ID))
        if (dir == null || key == null || platformId == null) {
            finish()
            return
        }
        gameDir = dir
        platform = platformId
        crc = intent.getLongExtra(GameSettingsActivity.EXTRA_CRC, 0)
        api = SteamGridDb(key, UrlHttp())

        page(getString(R.string.artwork_find), listOf("▲ ▼ ◀ ▶" to "Choose", "A" to "Pick", "B" to "Back"))
        query = field(getString(R.string.artwork_search), InputType.TYPE_CLASS_TEXT)
        query.setText(intent.getStringExtra(GameSettingsActivity.EXTRA_TITLE).orEmpty())
        val go = button(getString(R.string.artwork_search_go), Pixel.YELLOW) { search() }
        content.addView(go, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, spaced(8))
        focusFirst(go)
        search()
    }

    private fun message(text: String, error: Boolean = true) {
        results.removeAllViews()
        results.addView(note(text, top = 8).also { content.removeView(it) }.apply {
            setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        })
    }

    private fun search() {
        val name = query.text.toString().trim()
        if (name.isEmpty()) return
        message(getString(R.string.artwork_searching), error = false)
        io.execute {
            val result = api.search(name)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Sgdb.Failed -> message(failText(result.reason))
                    is Sgdb.Ok -> if (result.value.isEmpty()) message(getString(R.string.artwork_no_results)) else showGames(result.value.take(8))
                }
            }
        }
    }

    private fun failText(reason: Sgdb.Reason) = getString(
        when (reason) {
            Sgdb.Reason.KEY_REJECTED -> R.string.scraping_key_rejected
            Sgdb.Reason.SLOW_DOWN -> R.string.scraping_slow_down
            Sgdb.Reason.NOT_FOUND -> R.string.artwork_no_results
            Sgdb.Reason.OFFLINE -> R.string.scraping_offline
        },
    )

    private fun showGames(games: List<SgdbGame>) {
        results.removeAllViews()
        var first: View? = null
        for (game in games) {
            val summary = game.year?.toString().orEmpty()
            val (card, _) = menuCard(R.drawable.ic_settings, Pixel.PINK, game.name, summary) { loadGrids(game) }
            content.removeView(card)
            results.addView(card, spaced(12))
            if (first == null) first = card
        }
        first?.requestFocus()
    }

    private fun loadGrids(game: SgdbGame) {
        message(getString(R.string.artwork_searching), error = false)
        io.execute {
            val result = api.grids(game.id)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Sgdb.Failed -> message(failText(result.reason))
                    is Sgdb.Ok -> if (result.value.isEmpty()) message(getString(R.string.artwork_no_results)) else showGrids(result.value.take(24))
                }
            }
        }
    }

    private fun showGrids(grids: List<SgdbGrid>) {
        results.removeAllViews()
        results.addView(label(getString(R.string.artwork_pick_hint)), spaced(4))
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), dp(12), dp(14)) }
        var first: View? = null
        for (grid in grids) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = PixelBoxDrawable(this@ArtworkPickerActivity, Pixel.LAVENDER, shadowDp = 4f, shadowColor = SHADE, focusedFill = Pixel.YELLOW)
                setPadding(dp(5), dp(5), dp(9), dp(9))
                isFocusable = true
                isClickable = true
                setOnClickListener { choose(grid) }
            }
            strip.addView(thumb, LinearLayout.LayoutParams(dp(120), dp(160)).apply { marginEnd = dp(12) })
            if (first == null) first = thumb
            io.execute {
                val bytes = (api.download(grid.thumb) as? Sgdb.Ok)?.value ?: return@execute
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                runOnUiThread { if (!isDestroyed) thumb.setImageBitmap(bitmap) }
            }
        }
        results.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        first?.requestFocus()
    }

    private fun choose(grid: SgdbGrid) {
        message(getString(R.string.artwork_searching), error = false)
        io.execute {
            val stored = (api.download(grid.url) as? Sgdb.Ok)?.value?.let(ArtworkImages::encode)?.also {
                Artwork.store(this).put(platform.id, crc, it, "steamgriddb", grid.id.toString())
                GameSettings(File(gameDir, "settings.json")).put(GameSettingsKeys.ARTWORK, GameSettingsKeys.ARTWORK_PICKED)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (stored != null) {
                    Toast.makeText(this, R.string.artwork_saved, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    message(getString(R.string.artwork_save_failed))
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
