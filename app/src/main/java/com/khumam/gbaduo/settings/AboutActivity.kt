package com.khumam.gbaduo.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import com.khumam.gbaduo.BuildConfig
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.ui.Pixel

/** Settings > About: what GBADuo is, its version, source, and what it's built with. */
class AboutActivity : PixelPage() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.about_title), hints = listOf("A" to "Open", "B" to "Back"))

        content.addView(hud(listOf("GBADuo" to "V${BuildConfig.VERSION_NAME}", "Build" to BuildConfig.VERSION_CODE.toString().padStart(3, '0'))),
            spaced(0, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogBox(getString(R.string.app_name)) {
            addView(boxText(getString(R.string.about_tagline), 15f))
            addView(boxText(getString(R.string.about_disclaimer), 13f, top = 12))
        }

        val source = button(getString(R.string.about_source), Pixel.YELLOW) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))) }
        }
        content.addView(source, spaced(24, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(label(getString(R.string.about_credits_title)), spaced(28))
        note(getString(R.string.about_credits), top = 6)
        focusFirst(source)
    }
}
