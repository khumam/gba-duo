package com.khumam.gbaduo.controls

import android.view.KeyEvent
import com.khumam.gbaduo.emu.GbaKeys

/** The things a hotkey can do, in the order the Hotkeys page lists them. */
enum class HotkeyAction { QUICK_MENU, FAST_FORWARD, QUICK_SAVE, QUICK_LOAD, NEXT_SLOT, PREV_SLOT, EXIT }

enum class FastForwardMode { HOLD, TOGGLE }

/** What triggers an action: its key alone, or its key while the hotkey button is held. */
sealed interface Binding {
    val keyCode: Int

    data class Single(override val keyCode: Int) : Binding
    data class WithHotkey(override val keyCode: Int) : Binding
}

/** The `GbaKeys` bits in the order the Button mapping page lists them. */
val GAME_BUTTONS: List<Int> = listOf(
    GbaKeys.A, GbaKeys.B, GbaKeys.L, GbaKeys.R, GbaKeys.START, GbaKeys.SELECT,
    GbaKeys.UP, GbaKeys.DOWN, GbaKeys.LEFT, GbaKeys.RIGHT,
)

sealed interface Target {
    data class Button(val bit: Int) : Target
    data class Action(val action: HotkeyAction) : Target
}

/** A key that changed owner as a side effect of an assignment. [keyCode] 0 means the owner was cleared instead. */
data class Moved(val keyCode: Int, val to: Target)

enum class Refusal { BACK_KEY, IS_HOTKEY_BUTTON, WOULD_LEAVE_BUTTON_EMPTY }

sealed interface Change {
    data class Done(val scheme: ControlScheme, val moved: Moved?) : Change
    data class Refused(val reason: Refusal) : Change
}

/**
 * Which built-in button does what. Immutable and pure: every edit returns a new scheme.
 *
 * One button has at most one job: a game button, or a [Binding.Single] action. Giving a
 * used button to something else swaps the two. [Binding.WithHotkey] bindings only count
 * while the hotkey button is held, so they may reuse any key except the hotkey button.
 * Back is not part of the scheme: it always opens the quick menu.
 *
 * @param buttons `GbaKeys` bit → key code.
 * @param actions an action that's missing is unassigned.
 */
data class ControlScheme(
    val buttons: Map<Int, Int>,
    val hotkeyButton: Int?,
    val actions: Map<HotkeyAction, Binding>,
    val ffMode: FastForwardMode,
) {
    fun gameBitFor(keyCode: Int): Int? = buttons.entries.firstOrNull { it.value == keyCode }?.key

    fun singleActionFor(keyCode: Int): HotkeyAction? =
        actions.entries.firstOrNull { (it.value as? Binding.Single)?.keyCode == keyCode }?.key

    fun hotkeyActionFor(keyCode: Int): HotkeyAction? =
        actions.entries.firstOrNull { (it.value as? Binding.WithHotkey)?.keyCode == keyCode }?.key

    fun assignButton(bit: Int, keyCode: Int): Change {
        if (keyCode == KeyEvent.KEYCODE_BACK) return Change.Refused(Refusal.BACK_KEY)
        val previous = buttons.getValue(bit)
        if (previous == keyCode) return Change.Done(this, null)
        gameBitFor(keyCode)?.let { other ->
            val next = copy(buttons = buttons + (bit to keyCode) + (other to previous))
            return Change.Done(next, Moved(previous, Target.Button(other)))
        }
        singleActionFor(keyCode)?.let { action ->
            val next = copy(buttons = buttons + (bit to keyCode), actions = actions + (action to Binding.Single(previous)))
            return Change.Done(next, Moved(previous, Target.Action(action)))
        }
        return Change.Done(copy(buttons = buttons + (bit to keyCode)), null)
    }

    /** [binding] null unassigns [action]. */
    fun assignAction(action: HotkeyAction, binding: Binding?): Change {
        if (binding == null) return Change.Done(copy(actions = actions - action), null)
        val key = binding.keyCode
        if (key == KeyEvent.KEYCODE_BACK) return Change.Refused(Refusal.BACK_KEY)
        if (key == hotkeyButton) return Change.Refused(Refusal.IS_HOTKEY_BUTTON)
        val previous = actions[action]
        if (previous == binding) return Change.Done(this, null)

        if (binding is Binding.Single) {
            gameBitFor(key)?.let { bit ->
                val give = (previous as? Binding.Single)?.keyCode ?: return Change.Refused(Refusal.WOULD_LEAVE_BUTTON_EMPTY)
                val next = copy(buttons = buttons + (bit to give), actions = actions + (action to binding))
                return Change.Done(next, Moved(give, Target.Button(bit)))
            }
            singleActionFor(key)?.let { other -> return Change.Done(swapActions(action, binding, other, give(previous, binding)), moved(give(previous, binding), other)) }
        } else {
            hotkeyActionFor(key)?.let { other -> return Change.Done(swapActions(action, binding, other, give(previous, binding)), moved(give(previous, binding), other)) }
        }
        return Change.Done(copy(actions = actions + (action to binding)), null)
    }

    private fun swapActions(action: HotkeyAction, binding: Binding, other: HotkeyAction, give: Binding?): ControlScheme =
        copy(actions = (actions + (action to binding)).let { if (give != null) it + (other to give) else it - other })

    /** What the action that loses its key gets in return: this action's old binding, if it's the same kind. */
    private fun give(previous: Binding?, new: Binding): Binding? =
        previous?.takeIf { it::class == new::class }

    private fun moved(give: Binding?, other: HotkeyAction) = Moved(give?.keyCode ?: 0, Target.Action(other))

    /** Actions [setHotkeyButton] would unassign, so the page can ask first. */
    fun actionsUnassignedBy(keyCode: Int): List<HotkeyAction> =
        HotkeyAction.values().filter { actions[it]?.keyCode == keyCode }

    /** [keyCode] null means no hotkey button, which also drops every combo (they'd have nothing to hold). */
    fun setHotkeyButton(keyCode: Int?): Change {
        if (keyCode == KeyEvent.KEYCODE_BACK) return Change.Refused(Refusal.BACK_KEY)
        val kept = actions.filterValues { b ->
            if (keyCode == null) b !is Binding.WithHotkey else b.keyCode != keyCode
        }
        return Change.Done(copy(hotkeyButton = keyCode, actions = kept), null)
    }

    fun withFfMode(mode: FastForwardMode): ControlScheme = copy(ffMode = mode)

    /** The default game buttons. A Single action on a key a default button now uses becomes unassigned. */
    fun withDefaultButtons(): ControlScheme {
        val keys = DEFAULT.buttons.values.toSet()
        return copy(buttons = DEFAULT.buttons, actions = actions.filterValues { !(it is Binding.Single && it.keyCode in keys) })
    }

    /** The default hotkey button (none), actions and fast-forward mode. A default on a key a game button uses stays unassigned. */
    fun withDefaultHotkeys(): ControlScheme {
        val used = buttons.values.toSet()
        return copy(
            hotkeyButton = null,
            ffMode = DEFAULT.ffMode,
            actions = DEFAULT.actions.filterValues { !(it is Binding.Single && it.keyCode in used) },
        )
    }

    companion object {
        val DEFAULT = ControlScheme(
            buttons = mapOf(
                GbaKeys.A to KeyEvent.KEYCODE_BUTTON_A,
                GbaKeys.B to KeyEvent.KEYCODE_BUTTON_B,
                GbaKeys.L to KeyEvent.KEYCODE_BUTTON_L1,
                GbaKeys.R to KeyEvent.KEYCODE_BUTTON_R1,
                GbaKeys.START to KeyEvent.KEYCODE_BUTTON_START,
                GbaKeys.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
                GbaKeys.UP to KeyEvent.KEYCODE_DPAD_UP,
                GbaKeys.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                GbaKeys.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
                GbaKeys.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
            hotkeyButton = null,
            actions = mapOf(
                HotkeyAction.QUICK_MENU to Binding.Single(KeyEvent.KEYCODE_BUTTON_L2),
                HotkeyAction.FAST_FORWARD to Binding.Single(KeyEvent.KEYCODE_BUTTON_R2),
            ),
            ffMode = FastForwardMode.HOLD,
        )
    }
}
