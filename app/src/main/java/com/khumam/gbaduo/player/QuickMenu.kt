package com.khumam.gbaduo.player

import android.app.AlertDialog
import android.content.Context
import com.khumam.gbaduo.R
import com.khumam.gbaduo.emu.EmuThread
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.TiltShift
import java.text.DateFormat
import java.util.Date

/** Pause-and-choose overlay: save/load state slots, fast-forward speed, shader, tilt-shift, integer scaling, quit. */
class QuickMenu(
    private val context: Context,
    private val emu: EmuThread,
    private val states: SaveStates,
    initialShader: ShaderPreset,
    initialTilt: TiltShift,
    initialIntegerScaling: Boolean,
    private val onSetFfSpeed: (Int) -> Unit,
    private val onSetShader: (ShaderPreset) -> Unit,
    private val onSetTiltShift: (TiltShift) -> Unit,
    private val onSetIntegerScaling: (Boolean) -> Unit,
    private val onQuit: () -> Unit,
) {
    private var ffSpeed = 2
    private var shader = initialShader
    private var tilt = initialTilt
    private var integerScaling = initialIntegerScaling
    private var dialog: AlertDialog? = null

    /** True while the menu (or its slot picker) is open; it has input focus then. */
    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        emu.paused = true
        val items = listOf(
            context.getString(R.string.resume),
            context.getString(R.string.save_state),
            context.getString(R.string.load_state),
            context.getString(R.string.ff_speed, ffSpeed),
            context.getString(R.string.shader_label, shader.displayName),
            context.getString(R.string.tilt_label, tilt.displayName),
            context.getString(if (integerScaling) R.string.scale_label_on else R.string.scale_label_off),
            context.getString(R.string.quit),
        ).toTypedArray()

        dialog = AlertDialog.Builder(context)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> emu.paused = false
                    1 -> showSlotPicker(save = true)
                    2 -> showSlotPicker(save = false)
                    3 -> cycleFfSpeed()
                    4 -> cycleShader()
                    5 -> cycleTiltShift()
                    6 -> toggleIntegerScaling()
                    7 -> onQuit()
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

    private fun cycleTiltShift() {
        val all = TiltShift.entries
        tilt = all[(all.indexOf(tilt) + 1) % all.size]
        onSetTiltShift(tilt)
        emu.paused = false
    }

    private fun toggleIntegerScaling() {
        integerScaling = !integerScaling
        onSetIntegerScaling(integerScaling)
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
        }.toMutableList()
        // Loading only: the state made before a companion pack first changed the game.
        val beforePack = if (save) null else states.beforePackWrite
        if (beforePack != null) {
            labels += context.getString(R.string.state_before_pack, DateFormat.getDateTimeInstance().format(Date(beforePack.lastModified())))
        }

        dialog = AlertDialog.Builder(context)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 9 && beforePack != null) {
                    emu.requestState(beforePack.absolutePath, save = false)
                } else {
                    emu.requestState(states.slotPath(which + 1), save)
                }
                emu.paused = false
            }
            .setOnCancelListener { emu.paused = false }
            .show()
    }
}
