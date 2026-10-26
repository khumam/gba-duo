package com.khumam.gbaduo.settings

import android.os.Bundle
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.controls.Binding
import com.khumam.gbaduo.controls.Change
import com.khumam.gbaduo.controls.ControlPrefs
import com.khumam.gbaduo.controls.ControlScheme
import com.khumam.gbaduo.controls.GAME_BUTTONS
import com.khumam.gbaduo.controls.KeyCapture
import com.khumam.gbaduo.controls.KeyNames
import com.khumam.gbaduo.controls.Refusal
import com.khumam.gbaduo.controls.Target
import com.khumam.gbaduo.library.ui.Pixel
import android.view.ViewGroup

/** Settings > Controller > Button mapping: which built-in button presses each game button. */
class ButtonMappingActivity : CapturePage() {

    private lateinit var scheme: ControlScheme
    private val values = mutableMapOf<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.controls_buttons_title), listOf("▲ ▼" to "Choose", "A" to "Change", "B" to "Back"))
        // Results show here (and as a toast); the "press a button" prompt is a modal.
        addStatus(top = 0)
        scheme = ControlPrefs.load(this)

        var first: android.view.View? = null
        for (bit in GAME_BUTTONS) {
            val (card, value) = menuCard(R.drawable.ic_settings, Pixel.YELLOW,
                KeyNames.gameButton(bit), KeyNames.name(scheme.buttons.getValue(bit))) { change(bit) }
            values[bit] = value
            if (first == null) first = card
        }
        content.addView(button(getString(R.string.controls_reset), Pixel.WHITE_SOFT) { reset() },
            spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))
        focusFirst(first!!)
    }

    private fun render() {
        for ((bit, view) in values) view.text = KeyNames.name(scheme.buttons.getValue(bit))
    }

    private fun change(bit: Int) {
        beginCapture(KeyCapture(scheme.hotkeyButton, allowCombo = false), KeyNames.gameButton(bit)) { binding ->
            val key = (binding as? Binding.Single)?.keyCode ?: return@beginCapture
            when (val result = scheme.assignButton(bit, key)) {
                is Change.Done -> {
                    scheme = result.scheme
                    ControlPrefs.save(this, scheme)
                    render()
                    result.moved?.let { moved ->
                        val target = when (val to = moved.to) {
                            is Target.Button -> KeyNames.gameButton(to.bit)
                            is Target.Action -> getString(to.action.labelRes())
                        }
                        showStatus(getString(R.string.controls_moved, KeyNames.name(moved.keyCode), target))
                    }
                }
                is Change.Refused -> showStatus(
                    when (result.reason) {
                        Refusal.BACK_KEY -> getString(R.string.controls_refused_back)
                        else -> getString(R.string.controls_refused_hotkey)
                    },
                    error = true,
                )
            }
        }
    }

    private fun reset() {
        scheme = scheme.withDefaultButtons()
        ControlPrefs.save(this, scheme)
        render()
        showStatus(getString(R.string.controls_reset_done))
    }
}
