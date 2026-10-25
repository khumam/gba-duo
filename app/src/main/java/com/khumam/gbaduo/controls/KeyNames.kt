package com.khumam.gbaduo.controls

import android.view.KeyEvent
import com.khumam.gbaduo.emu.GbaKeys

/** Names a player would recognise for built-in buttons. Pure. */
object KeyNames {

    fun name(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Home"
        else -> fallback(keyCode)
    }

    /** What the GBA calls its buttons. */
    fun gameButton(bit: Int): String = when (bit) {
        GbaKeys.A -> "A"
        GbaKeys.B -> "B"
        GbaKeys.L -> "L"
        GbaKeys.R -> "R"
        GbaKeys.START -> "Start"
        GbaKeys.SELECT -> "Select"
        GbaKeys.UP -> "Up"
        GbaKeys.DOWN -> "Down"
        GbaKeys.LEFT -> "Left"
        else -> "Right"
    }

    private fun fallback(keyCode: Int): String {
        val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrNull() ?: return "Key $keyCode"
        return raw.removePrefix("KEYCODE_").lowercase().split('_')
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    /** "R1", "Select + R1", or [notSet] for no binding. */
    fun describe(binding: Binding?, hotkeyButton: Int?, notSet: String): String = when (binding) {
        null -> notSet
        is Binding.Single -> name(binding.keyCode)
        is Binding.WithHotkey -> (hotkeyButton?.let { name(it) + " + " } ?: "") + name(binding.keyCode)
    }
}
