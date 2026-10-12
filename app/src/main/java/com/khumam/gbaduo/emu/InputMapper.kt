package com.khumam.gbaduo.emu

import android.view.KeyEvent

/** Maps Android gamepad/keyboard key codes to a GBA key bitmask. Not thread-safe; call from one thread. */
class InputMapper {
    var swapAb: Boolean = false

    private var pressed = 0
    private var r2Held = false

    /** True for a mapped key (caller should consume the event); false otherwise. */
    fun onKey(keyCode: Int, down: Boolean): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            r2Held = down
            return true
        }
        val bit = bitFor(keyCode) ?: return false
        pressed = if (down) pressed or bit else pressed and bit.inv()
        return true
    }

    fun keys(): Int = pressed

    fun fastForward(): Boolean = r2Held

    private fun bitFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_B -> if (swapAb) GbaKeys.B else GbaKeys.A
        KeyEvent.KEYCODE_BUTTON_A -> if (swapAb) GbaKeys.A else GbaKeys.B
        KeyEvent.KEYCODE_BUTTON_L1 -> GbaKeys.L
        KeyEvent.KEYCODE_BUTTON_R1 -> GbaKeys.R
        KeyEvent.KEYCODE_BUTTON_START -> GbaKeys.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> GbaKeys.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> GbaKeys.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GbaKeys.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GbaKeys.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GbaKeys.RIGHT
        else -> null
    }
}
