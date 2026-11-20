package com.khumam.gbaduo.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.CompanionMode
import com.khumam.gbaduo.companion.CompanionSettings
import com.khumam.gbaduo.library.ui.Pixel

/**
 * Settings > Companion screen: what the second screen shows while a game runs. Off leaves
 * it to other apps, Blank with art keeps it dark with the game's cartridge, Active is the
 * pack's companion screen. The next game started uses the choice.
 */
class CompanionScreenActivity : PixelPage() {

    private class Option(val mode: CompanionMode, val title: Int, val summary: Int, val tile: Int)

    private val options = listOf(
        Option(CompanionMode.ACTIVE, R.string.companion_active, R.string.companion_active_summary, Pixel.MINT),
        Option(CompanionMode.BLANK_ART, R.string.companion_blank, R.string.companion_blank_summary, Pixel.PURPLE_LIGHT),
        Option(CompanionMode.OFF, R.string.companion_off, R.string.companion_off_summary, Pixel.CORAL),
    )
    private val summaries = mutableMapOf<CompanionMode, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.companion_title))
        note(getString(R.string.companion_intro), top = 0)
        var first: View? = null
        for (option in options) {
            val (card, summary) = menuCard(R.drawable.ic_settings, option.tile, getString(option.title), getString(option.summary)) {
                CompanionSettings.setMode(this, option.mode)
                render()
            }
            if (first == null) first = card
            summaries[option.mode] = summary
        }
        note(getString(R.string.companion_note), top = 16)
        render()
        first?.let(::focusFirst)
    }

    /** The chosen option's summary leads with a check mark. */
    private fun render() {
        val current = CompanionSettings.mode(this)
        for (option in options) {
            val text = getString(option.summary)
            summaries[option.mode]?.text = if (option.mode == current) getString(R.string.companion_selected, text) else text
        }
    }
}
