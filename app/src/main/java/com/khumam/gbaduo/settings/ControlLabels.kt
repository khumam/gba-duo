package com.khumam.gbaduo.settings

import com.khumam.gbaduo.R
import com.khumam.gbaduo.controls.HotkeyAction

/** The string for an action's row and messages. */
fun HotkeyAction.labelRes(): Int = when (this) {
    HotkeyAction.QUICK_MENU -> R.string.hotkey_quick_menu
    HotkeyAction.FAST_FORWARD -> R.string.hotkey_fast_forward
    HotkeyAction.QUICK_SAVE -> R.string.hotkey_quick_save
    HotkeyAction.QUICK_LOAD -> R.string.hotkey_quick_load
    HotkeyAction.NEXT_SLOT -> R.string.hotkey_next_slot
    HotkeyAction.PREV_SLOT -> R.string.hotkey_prev_slot
    HotkeyAction.EXIT -> R.string.hotkey_exit
}
