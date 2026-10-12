package com.khumam.gbaduo.player

import android.app.AlertDialog
import android.content.Context
import com.khumam.gbaduo.R
import com.khumam.gbaduo.emu.EmuThread
import com.khumam.gbaduo.emu.ShaderPreset
import java.text.DateFormat
import java.util.Date

/** Pause-and-choose overlay: save/load state slots, fast-forward speed, shader, swap A/B, quit. */
class QuickMenu(
    private val context: Context,
    private val emu: EmuThread,
    private val states: SaveStates,
    initialShader: ShaderPreset,
    private val onSwapAb: (Boolean) -> Unit,
    private val onSetFfSpeed: (Int) -> Unit,
    private val onSetShader: (ShaderPreset) -> Unit,
    private val onQuit: () -> Unit,
) {
    private var swapAb = false
    private var ffSpeed = 2
    private var shader = initialShader

    fun show() {
        emu.paused = true
        val items = listOf(
            context.getString(R.string.resume),
            context.getString(R.string.save_state),
            context.getString(R.string.load_state),
            context.getString(R.string.ff_speed, ffSpeed),
            context.getString(R.string.shader_label, shader.displayName),
            context.getString(R.string.swap_ab),
            context.getString(R.string.quit),
        ).toTypedArray()

        AlertDialog.Builder(context)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> emu.paused = false
                    1 -> showSlotPicker(save = true)
                    2 -> showSlotPicker(save = false)
                    3 -> cycleFfSpeed()
                    4 -> cycleShader()
                    5 -> { swapAb = !swapAb; onSwapAb(swapAb); emu.paused = false }
                    6 -> onQuit()
                }
            }
            .setOnCancelListener { emu.paused = false }
            .show()
    }

    private fun cycleFfSpeed() {
        ffSpeed = when (ffSpeed) { 2 -> 3; 3 -> 4; else -> 2 }
        onSetFfSpeed(ffSpeed)
        emu.paused = false
    }

    private fun cycleShader() {
        shader = ShaderPreset.next(shader)
        onSetShader(shader)
        emu.paused = false
    }

    private fun showSlotPicker(save: Boolean) {
        val labels = (1..9).map { slot ->
            if (states.slotExists(slot)) {
                val whenText = DateFormat.getDateTimeInstance().format(Date(states.slotModified(slot)))
                context.getString(R.string.slot_n, slot) + " — $whenText"
            } else {
                context.getString(R.string.slot_empty, slot)
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setItems(labels) { _, which ->
                val slot = which + 1
                emu.requestState(states.slotPath(slot), save)
                emu.paused = false
            }
            .setOnCancelListener { emu.paused = false }
            .show()
    }
}
