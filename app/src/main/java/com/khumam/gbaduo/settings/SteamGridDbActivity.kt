package com.khumam.gbaduo.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.artwork.ArtworkPrefs
import com.khumam.gbaduo.library.artwork.Sgdb
import com.khumam.gbaduo.library.artwork.SteamGridDb
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import java.util.concurrent.Executors

/** Providers > SteamGridDB: the player's own API key, saved as typed and testable. */
class SteamGridDbActivity : PixelPage() {

    private lateinit var keyField: EditText
    private lateinit var keyStatus: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.provider_sgdb))
        note(getString(R.string.scraping_sgdb_note), top = 0)
        keyField = field(
            getString(R.string.scraping_sgdb_key),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            password = true,
        )
        keyField.setText(ArtworkPrefs.sgdbKey(this).orEmpty())
        val test = button(getString(R.string.scraping_test_key), Pixel.YELLOW) { testKey() }
        content.addView(test, spaced(16, ViewGroup.LayoutParams.WRAP_CONTENT))
        keyStatus = TextView(this).apply {
            typeface = Fonts.body(this@SteamGridDbActivity, 600)
            textSize = 14f
            setTextColor(Pixel.ON_INK_SOFT)
            visibility = View.GONE
        }
        content.addView(keyStatus, spaced(14))
        focusFirst(test)
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

    private fun show(text: String, error: Boolean = false) {
        keyStatus.text = text
        keyStatus.setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        keyStatus.visibility = View.VISIBLE
    }

    private fun testKey() {
        val key = saveKey() ?: return show(getString(R.string.scraping_key_missing), error = true)
        io.execute {
            val result = SteamGridDb(key, UrlHttp()).testKey()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Sgdb.Ok -> show(getString(R.string.scraping_key_ok))
                    is Sgdb.Failed -> show(
                        getString(
                            when (result.reason) {
                                Sgdb.Reason.KEY_REJECTED -> R.string.scraping_key_rejected
                                Sgdb.Reason.SLOW_DOWN -> R.string.scraping_slow_down
                                else -> R.string.scraping_offline
                            },
                        ),
                        error = true,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
