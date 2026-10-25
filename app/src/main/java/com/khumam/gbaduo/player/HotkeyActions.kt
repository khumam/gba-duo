package com.khumam.gbaduo.player

import android.app.Activity
import android.app.AlertDialog
import com.khumam.gbaduo.R
import com.khumam.gbaduo.controls.HotkeyAction
import com.khumam.gbaduo.emu.EmuThread
import com.khumam.gbaduo.storage.GameStorage

/**
 * Carries out the hotkey actions the [com.khumam.gbaduo.emu.InputMapper] hands back
 * (everything except fast-forward). Main thread only. Results of state saves and loads
 * arrive on the emu thread, so their notes are posted to the UI thread.
 */
class HotkeyActions(
    private val activity: Activity,
    private val emu: EmuThread,
    private val states: SaveStates,
    private val storage: GameStorage,
    private val quickMenu: QuickMenu,
    private val notes: PlayerNotes,
    private val onQuit: () -> Unit,
) {

    fun perform(action: HotkeyAction) {
        when (action) {
            HotkeyAction.QUICK_MENU -> quickMenu.show()
            HotkeyAction.QUICK_SAVE -> quickSave()
            HotkeyAction.QUICK_LOAD -> quickLoad()
            HotkeyAction.NEXT_SLOT -> moveSlot(+1)
            HotkeyAction.PREV_SLOT -> moveSlot(-1)
            HotkeyAction.EXIT -> confirmExit()
            HotkeyAction.FAST_FORWARD -> Unit // handled by the InputMapper
        }
    }

    private fun note(textRes: Int, slot: Int) {
        val text = activity.getString(textRes, slot)
        activity.runOnUiThread { notes.show(text) }
    }

    private fun quickSave() {
        val slot = storage.currentSlot
        emu.requestState(states.slotPath(slot), save = true) { ok ->
            note(if (ok) R.string.hotkey_saved else R.string.hotkey_save_failed, slot)
        }
    }

    private fun quickLoad() {
        val slot = storage.currentSlot
        if (!states.slotExists(slot)) {
            note(R.string.hotkey_slot_empty, slot)
            return
        }
        emu.requestState(states.slotPath(slot), save = false) { ok ->
            note(if (ok) R.string.hotkey_loaded else R.string.hotkey_load_failed, slot)
        }
    }

    private fun moveSlot(step: Int) {
        val slot = (storage.currentSlot - 1 + step + SLOTS) % SLOTS + 1
        storage.currentSlot = slot
        note(if (states.slotExists(slot)) R.string.hotkey_slot else R.string.hotkey_slot_none, slot)
    }

    private fun confirmExit() {
        emu.paused = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.hotkey_exit_title)
            .setMessage(R.string.hotkey_exit_message)
            .setPositiveButton(R.string.quit) { _, _ -> onQuit() }
            .setNegativeButton(R.string.hotkey_keep_playing, null)
            .setOnCancelListener { emu.paused = false }
            .setOnDismissListener { if (!activity.isFinishing) emu.paused = false }
            .show()
    }

    private companion object {
        const val SLOTS = 9
    }
}
