package com.khumam.gbaduo.settings

import android.content.Intent
import android.os.Bundle
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.ui.Pixel

/** Settings > Controller: the list that leads to Button mapping and Hotkeys. */
class ControllerActivity : PixelPage() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.controls_title))
        val (buttons, _) = menuCard(R.drawable.ic_settings, Pixel.YELLOW,
            getString(R.string.controls_buttons_title), getString(R.string.controls_buttons_summary)) {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }
        menuCard(R.drawable.ic_settings, Pixel.MINT,
            getString(R.string.controls_hotkeys_title), getString(R.string.controls_hotkeys_summary)) {
            startActivity(Intent(this, HotkeysActivity::class.java))
        }
        focusFirst(buttons)
    }
}
