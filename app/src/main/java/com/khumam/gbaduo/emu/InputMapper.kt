package com.khumam.gbaduo.emu

import com.khumam.gbaduo.controls.ControlScheme
import com.khumam.gbaduo.controls.FastForwardMode
import com.khumam.gbaduo.controls.HotkeyAction
import java.util.concurrent.atomic.AtomicInteger

/** What [InputMapper.onKey] did with a key event. */
sealed interface KeyResult {
    /** Not ours: let the system handle it. */
    object Ignored : KeyResult

    /** Handled (a game key, or swallowed by a hotkey hold): nothing more to do. */
    object Consumed : KeyResult

    /** A hotkey action to carry out. Never [HotkeyAction.FAST_FORWARD]: the mapper handles that itself. */
    data class Action(val action: HotkeyAction) : KeyResult
}

/**
 * Routes key events through a [ControlScheme] into a GBA key bitmask and hotkey actions.
 * [onKey] and [releaseAll] are called from the main thread only; the emu thread reads
 * [keys] and [fastForward] each frame, so what it reads is `@Volatile`.
 *
 * Order for a key event: repeats, the hotkey button, any key while the hotkey button is
 * held (swallowed, or its combo action), keys swallowed earlier (their release), single-key
 * actions, game buttons. While the hotkey button is held nothing new reaches the game; a
 * hotkey button that's also a game button reaches it as a short tap when released alone.
 */
class InputMapper(private val scheme: ControlScheme = ControlScheme.DEFAULT) {

    @Volatile private var pressed = 0
    @Volatile private var ffHeld = false
    @Volatile private var ffToggled = false

    private var hotkeyDown = false
    private var comboUsed = false
    private var ffKey: Int? = null
    private val swallowed = mutableSetOf<Int>()

    private val tapFrames = AtomicInteger(0)
    @Volatile private var tapBit = 0

    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int = 0): KeyResult {
        if (repeatCount > 0) return if (handles(keyCode)) KeyResult.Consumed else KeyResult.Ignored

        if (keyCode == scheme.hotkeyButton) {
            if (down) {
                hotkeyDown = true
                comboUsed = false
            } else if (hotkeyDown) {
                hotkeyDown = false
                val bit = scheme.gameBitFor(keyCode)
                if (!comboUsed && bit != null) {
                    tapBit = bit
                    tapFrames.set(TAP_FRAMES)
                }
            }
            return KeyResult.Consumed
        }

        if (down && hotkeyDown) {
            comboUsed = true
            swallowed += keyCode
            val action = scheme.hotkeyActionFor(keyCode) ?: return KeyResult.Consumed
            if (action == HotkeyAction.FAST_FORWARD) {
                fastForwardKey(keyCode, down = true)
                return KeyResult.Consumed
            }
            return KeyResult.Action(action)
        }

        if (!down && swallowed.remove(keyCode)) {
            if (keyCode == ffKey) fastForwardKey(keyCode, down = false)
            return KeyResult.Consumed
        }

        val action = scheme.singleActionFor(keyCode)
        if (action != null) {
            if (action == HotkeyAction.FAST_FORWARD) {
                fastForwardKey(keyCode, down)
                return KeyResult.Consumed
            }
            return if (down) KeyResult.Action(action) else KeyResult.Consumed
        }

        val bit = scheme.gameBitFor(keyCode) ?: return KeyResult.Ignored
        pressed = if (down) pressed or bit else pressed and bit.inv()
        return KeyResult.Consumed
    }

    private fun handles(keyCode: Int): Boolean =
        keyCode == scheme.hotkeyButton || hotkeyDown || keyCode in swallowed ||
            scheme.singleActionFor(keyCode) != null || scheme.gameBitFor(keyCode) != null

    private fun fastForwardKey(keyCode: Int, down: Boolean) {
        when (scheme.ffMode) {
            FastForwardMode.HOLD -> {
                ffHeld = down
                ffKey = if (down) keyCode else null
            }
            FastForwardMode.TOGGLE -> if (down) ffToggled = !ffToggled
        }
    }

    /**
     * Lifts every held key and forgets any hotkey hold. Call when the game window loses
     * focus (or the quick menu opens): the matching key-up may then go to another window,
     * which would leave the key stuck down.
     */
    fun releaseAll() {
        pressed = 0
        ffHeld = false
        ffToggled = false
        hotkeyDown = false
        comboUsed = false
        ffKey = null
        swallowed.clear()
        tapFrames.set(0)
    }

    /** Emu thread, once per frame. Includes the hotkey button's tap for [TAP_FRAMES] calls after its release. */
    fun keys(): Int {
        if (tapFrames.get() > 0) {
            tapFrames.decrementAndGet()
            return pressed or tapBit
        }
        return pressed
    }

    fun fastForward(): Boolean = ffHeld || ffToggled

    private companion object {
        const val TAP_FRAMES = 3
    }
}
