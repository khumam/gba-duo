package com.khumam.gbaduo.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.controls.Binding
import com.khumam.gbaduo.controls.Change
import com.khumam.gbaduo.controls.ControlPrefs
import com.khumam.gbaduo.controls.ControlScheme
import com.khumam.gbaduo.controls.FastForwardMode
import com.khumam.gbaduo.controls.HotkeyAction
import com.khumam.gbaduo.controls.KeyCapture
import com.khumam.gbaduo.controls.KeyNames
import com.khumam.gbaduo.controls.Refusal
import com.khumam.gbaduo.controls.Target
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelButton

/**
 * Settings > Controller > Hotkeys: the hotkey button (none by default) and the seven
 * actions, each a single button or hotkey button + button.
 */
class HotkeysActivity : CapturePage() {

    private lateinit var scheme: ControlScheme
    private lateinit var hotkeyValue: TextView
    private lateinit var hotkeyNote: TextView
    private lateinit var clearHotkey: PixelButton
    private lateinit var ffMode: PixelButton
    private val values = mutableMapOf<HotkeyAction, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.controls_hotkeys_title), listOf("▲ ▼" to "Choose", "A" to "Change", "Y" to "Clear", "B" to "Back"))
        // Results show here (and as a toast); the "press a button" prompt is a modal.
        addStatus(top = 0)
        scheme = ControlPrefs.load(this)

        val (hotkeyCard, hotkeyText) = menuCard(R.drawable.ic_settings, Pixel.PINK,
            getString(R.string.controls_hotkey_button), "") { changeHotkeyButton() }
        hotkeyValue = hotkeyText
        hotkeyNote = note("", top = 8)
        clearHotkey = button(getString(R.string.controls_clear_hotkey), Pixel.WHITE_SOFT) { setHotkeyButton(null) }
        content.addView(clearHotkey, spaced(10, ViewGroup.LayoutParams.WRAP_CONTENT))

        for (action in HotkeyAction.values()) {
            val (card, value) = menuCard(R.drawable.ic_settings, Pixel.YELLOW, getString(action.labelRes()), "") { change(action) }
            values[action] = value
            card.setOnLongClickListener { clear(action); true }
            card.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    clear(action)
                    true
                } else {
                    false
                }
            }
            if (action == HotkeyAction.FAST_FORWARD) {
                ffMode = button("", Pixel.LAVENDER) { toggleFfMode() }
                content.addView(ffMode, spaced(10, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        content.addView(button(getString(R.string.controls_reset), Pixel.WHITE_SOFT) { reset() },
            spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))
        render()
        focusFirst(hotkeyCard)
    }

    private fun save(next: ControlScheme) {
        scheme = next
        ControlPrefs.save(this, scheme)
        render()
    }

    private fun render() {
        val hotkey = scheme.hotkeyButton
        hotkeyValue.text = hotkey?.let { KeyNames.name(it) } ?: getString(R.string.controls_none)
        clearHotkey.visibility = if (hotkey == null) View.GONE else View.VISIBLE
        val gameBit = hotkey?.let { scheme.gameBitFor(it) }
        hotkeyNote.visibility = if (gameBit != null) View.VISIBLE else View.GONE
        hotkey?.let { hotkeyNote.text = getString(R.string.controls_hotkey_on_game, KeyNames.name(it)) }
        val notSet = getString(R.string.controls_unassigned)
        for ((action, view) in values) view.text = KeyNames.describe(scheme.actions[action], hotkey, notSet)
        ffMode.text = getString(
            R.string.controls_ff_mode,
            getString(if (scheme.ffMode == FastForwardMode.HOLD) R.string.controls_ff_hold else R.string.controls_ff_toggle),
        )
    }

    private fun changeHotkeyButton() {
        beginCapture(KeyCapture(scheme.hotkeyButton, allowCombo = false), getString(R.string.controls_hotkey_button)) { binding ->
            val key = (binding as? Binding.Single)?.keyCode ?: return@beginCapture
            val dropped = scheme.actionsUnassignedBy(key)
            if (dropped.isEmpty()) {
                setHotkeyButton(key)
                return@beginCapture
            }
            val names = dropped.joinToString(", ") { getString(it.labelRes()) }
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.controls_hotkey_unassigns, KeyNames.name(key), names))
                .setPositiveButton(R.string.controls_use) { _, _ -> setHotkeyButton(key) }
                .setNegativeButton(R.string.controls_cancel, null)
                .show()
        }
    }

    private fun setHotkeyButton(key: Int?) {
        when (val result = scheme.setHotkeyButton(key)) {
            is Change.Done -> save(result.scheme)
            is Change.Refused -> showStatus(getString(R.string.controls_refused_back), error = true)
        }
    }

    private fun change(action: HotkeyAction) {
        beginCapture(KeyCapture(scheme.hotkeyButton, allowCombo = scheme.hotkeyButton != null), getString(action.labelRes())) { binding ->
            binding ?: return@beginCapture
            when (val result = scheme.assignAction(action, binding)) {
                is Change.Done -> {
                    save(result.scheme)
                    result.moved?.let { moved ->
                        val target = when (val to = moved.to) {
                            is Target.Button -> KeyNames.gameButton(to.bit)
                            is Target.Action -> getString(to.action.labelRes())
                        }
                        showStatus(
                            if (moved.keyCode == 0) getString(R.string.controls_cleared, target)
                            else getString(R.string.controls_moved, KeyNames.name(moved.keyCode), target),
                        )
                    }
                }
                is Change.Refused -> showStatus(
                    when (result.reason) {
                        Refusal.BACK_KEY -> getString(R.string.controls_refused_back)
                        Refusal.IS_HOTKEY_BUTTON -> getString(R.string.controls_refused_hotkey)
                        Refusal.WOULD_LEAVE_BUTTON_EMPTY -> {
                            val key = binding.keyCode
                            val owner = scheme.gameBitFor(key)?.let { KeyNames.gameButton(it) } ?: ""
                            getString(R.string.controls_refused_empty, KeyNames.name(key), owner)
                        }
                    },
                    error = true,
                )
            }
        }
    }

    private fun clear(action: HotkeyAction) {
        (scheme.assignAction(action, null) as? Change.Done)?.let { save(it.scheme) }
    }

    private fun toggleFfMode() {
        save(scheme.withFfMode(if (scheme.ffMode == FastForwardMode.HOLD) FastForwardMode.TOGGLE else FastForwardMode.HOLD))
    }

    private fun reset() {
        save(scheme.withDefaultHotkeys())
        showStatus(getString(R.string.controls_reset_done))
    }
}
