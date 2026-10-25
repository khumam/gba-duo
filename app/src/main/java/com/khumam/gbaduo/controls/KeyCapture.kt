package com.khumam.gbaduo.controls

import android.view.KeyEvent

/**
 * "Press a button…" as a state machine, fed the key events and clock ticks of a settings
 * page. Pure. The capture finishes on the first key release. Back, or any key held for
 * [cancelAfterMs], cancels instead, so Back and long holds can never be assigned.
 *
 * @param hotkeyButton the hotkey button, to recognise "hotkey + button".
 * @param allowCombo true for hotkey action rows; game-button rows always capture a single key.
 */
class KeyCapture(
    private val hotkeyButton: Int?,
    private val allowCombo: Boolean,
    private val cancelAfterMs: Long = 2000,
) {
    sealed interface Outcome {
        object Waiting : Outcome
        object Cancelled : Outcome
        data class Captured(val binding: Binding) : Outcome
    }

    private var first: Int? = null
    private var combo: Int? = null
    private var hotkeyHeld = false
    private var firstDownAt = 0L

    fun onKey(keyCode: Int, down: Boolean, timeMs: Long): Outcome {
        if (keyCode == KeyEvent.KEYCODE_BACK) return if (down) Outcome.Cancelled else Outcome.Waiting
        if (down) {
            if (first == null) {
                first = keyCode
                firstDownAt = timeMs
            }
            if (allowCombo && hotkeyButton != null) {
                if (keyCode == hotkeyButton) hotkeyHeld = true
                else if (hotkeyHeld && combo == null) combo = keyCode
            }
            return Outcome.Waiting
        }
        val start = first ?: return Outcome.Waiting
        if (timeMs - firstDownAt >= cancelAfterMs) return Outcome.Cancelled
        val other = combo
        return Outcome.Captured(if (other != null) Binding.WithHotkey(other) else Binding.Single(start))
    }

    fun onTick(timeMs: Long): Outcome =
        if (first != null && timeMs - firstDownAt >= cancelAfterMs) Outcome.Cancelled else Outcome.Waiting
}
