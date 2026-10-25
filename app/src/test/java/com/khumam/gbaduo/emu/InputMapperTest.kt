package com.khumam.gbaduo.emu

import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_BUTTON_B
import android.view.KeyEvent.KEYCODE_BUTTON_L1
import android.view.KeyEvent.KEYCODE_BUTTON_L2
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_R2
import android.view.KeyEvent.KEYCODE_BUTTON_SELECT
import android.view.KeyEvent.KEYCODE_BUTTON_START
import android.view.KeyEvent.KEYCODE_BUTTON_X
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import com.khumam.gbaduo.controls.Binding
import com.khumam.gbaduo.controls.Change
import com.khumam.gbaduo.controls.ControlScheme
import com.khumam.gbaduo.controls.FastForwardMode
import com.khumam.gbaduo.controls.HotkeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class InputMapperTest {

    private fun scheme(block: (ControlScheme) -> ControlScheme): ControlScheme = block(ControlScheme.DEFAULT)

    private fun ControlScheme.done(change: Change): ControlScheme = (change as Change.Done).scheme

    private fun withHotkey(fast: Binding? = null): ControlScheme {
        var s = ControlScheme.DEFAULT
        s = s.done(s.setHotkeyButton(KEYCODE_BUTTON_SELECT))
        s = s.done(s.assignAction(HotkeyAction.QUICK_SAVE, Binding.WithHotkey(KEYCODE_BUTTON_R1)))
        if (fast != null) s = s.done(s.assignAction(HotkeyAction.FAST_FORWARD, fast))
        return s
    }

    @Test
    fun defaultFaceButtonsFollowLabels() {
        val mapper = InputMapper()
        mapper.onKey(KEYCODE_BUTTON_A, down = true)
        assertEquals(GbaKeys.A, mapper.keys())
        mapper.onKey(KEYCODE_BUTTON_A, down = false)
        mapper.onKey(KEYCODE_BUTTON_B, down = true)
        assertEquals(GbaKeys.B, mapper.keys())
    }

    @Test
    fun remappedKeyDrivesItsButton() {
        val s = ControlScheme.DEFAULT.let { it.done(it.assignButton(GbaKeys.A, KEYCODE_BUTTON_X)) }
        val mapper = InputMapper(s)
        mapper.onKey(KEYCODE_BUTTON_X, down = true)
        assertEquals(GbaKeys.A, mapper.keys())
        assertEquals(KeyResult.Consumed, mapper.onKey(KEYCODE_BUTTON_X, down = false))
        // BUTTON_A was swapped onto the button X used to be... X was free, so A is unused now.
        assertEquals(KeyResult.Ignored, mapper.onKey(KEYCODE_BUTTON_A, down = true))
    }

    @Test
    fun mapsShouldersStartSelectAndDpad() {
        val mapper = InputMapper()
        mapper.onKey(KEYCODE_BUTTON_L1, down = true)
        mapper.onKey(KEYCODE_BUTTON_R1, down = true)
        mapper.onKey(KEYCODE_BUTTON_START, down = true)
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = true)
        mapper.onKey(KEYCODE_DPAD_UP, down = true)
        assertEquals(GbaKeys.L or GbaKeys.R or GbaKeys.START or GbaKeys.SELECT or GbaKeys.UP, mapper.keys())
    }

    @Test
    fun releasingAKeyClearsItsBit() {
        val mapper = InputMapper()
        mapper.onKey(KEYCODE_DPAD_LEFT, down = true)
        mapper.onKey(KEYCODE_DPAD_LEFT, down = false)
        assertEquals(0, mapper.keys())
    }

    @Test
    fun unmappedKeyIsIgnored() {
        val mapper = InputMapper()
        assertEquals(KeyResult.Ignored, mapper.onKey(KEYCODE_VOLUME_UP, down = true))
        assertEquals(0, mapper.keys())
    }

    @Test
    fun singleActionReturnsAction() {
        val mapper = InputMapper()
        assertEquals(KeyResult.Action(HotkeyAction.QUICK_MENU), mapper.onKey(KEYCODE_BUTTON_L2, down = true))
        assertEquals(KeyResult.Consumed, mapper.onKey(KEYCODE_BUTTON_L2, down = false))
    }

    @Test
    fun holdFastForward() {
        val mapper = InputMapper()
        assertEquals(false, mapper.fastForward())
        mapper.onKey(KEYCODE_BUTTON_R2, down = true)
        assertEquals(true, mapper.fastForward())
        mapper.onKey(KEYCODE_BUTTON_R2, down = false)
        assertEquals(false, mapper.fastForward())
    }

    @Test
    fun toggleFastForward() {
        val mapper = InputMapper(ControlScheme.DEFAULT.withFfMode(FastForwardMode.TOGGLE))
        mapper.onKey(KEYCODE_BUTTON_R2, down = true)
        mapper.onKey(KEYCODE_BUTTON_R2, down = false)
        assertEquals(true, mapper.fastForward())
        mapper.onKey(KEYCODE_BUTTON_R2, down = true)
        mapper.onKey(KEYCODE_BUTTON_R2, down = false)
        assertEquals(false, mapper.fastForward())
    }

    @Test
    fun hotkeyComboFiresAndBlocksGame() {
        val mapper = InputMapper(withHotkey())
        assertEquals(KeyResult.Consumed, mapper.onKey(KEYCODE_BUTTON_SELECT, down = true))
        assertEquals(0, mapper.keys())
        assertEquals(KeyResult.Action(HotkeyAction.QUICK_SAVE), mapper.onKey(KEYCODE_BUTTON_R1, down = true))
        assertEquals(0, mapper.keys())
        mapper.onKey(KEYCODE_BUTTON_R1, down = false)
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = false)
        repeat(4) { assertEquals(0, mapper.keys()) }
    }

    @Test
    fun hotkeyTapReachesGame() {
        val mapper = InputMapper(withHotkey())
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = true)
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = false)
        repeat(3) { assertEquals(GbaKeys.SELECT, mapper.keys()) }
        assertEquals(0, mapper.keys())
    }

    @Test
    fun unboundKeyDuringHotkeyIsSwallowed() {
        val mapper = InputMapper(withHotkey())
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = true)
        assertEquals(KeyResult.Consumed, mapper.onKey(KEYCODE_BUTTON_A, down = true))
        assertEquals(0, mapper.keys())
    }

    @Test
    fun actionKeyReleasedAfterHotkeyStaysSwallowed() {
        val mapper = InputMapper(withHotkey(fast = Binding.WithHotkey(KEYCODE_BUTTON_R1)).let { s ->
            // R1 can only hold one combo: fast-forward takes it from quick save.
            s
        })
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = true)
        mapper.onKey(KEYCODE_BUTTON_R1, down = true)
        assertEquals(true, mapper.fastForward())
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = false)
        assertEquals(true, mapper.fastForward())
        assertEquals(KeyResult.Consumed, mapper.onKey(KEYCODE_BUTTON_R1, down = false))
        assertEquals(false, mapper.fastForward())
        assertEquals(0, mapper.keys() and GbaKeys.R)
    }

    @Test
    fun heldGameKeysSurviveHotkeyPress() {
        val mapper = InputMapper(withHotkey())
        mapper.onKey(KEYCODE_DPAD_RIGHT, down = true)
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = true)
        assertEquals(GbaKeys.RIGHT, mapper.keys() and GbaKeys.RIGHT)
    }

    @Test
    fun repeatsDoNotRefireActions() {
        val mapper = InputMapper()
        mapper.onKey(KEYCODE_BUTTON_L2, down = true)
        assertEquals(KeyResult.Consumed, mapper.onKey(KEYCODE_BUTTON_L2, down = true, repeatCount = 1))
    }

    @Test
    fun releaseAllClearsHotkeyState() {
        val mapper = InputMapper(withHotkey())
        mapper.onKey(KEYCODE_BUTTON_SELECT, down = true)
        mapper.releaseAll()
        mapper.onKey(KEYCODE_BUTTON_A, down = true)
        assertEquals(GbaKeys.A, mapper.keys())
    }

    @Test
    fun releaseAllLiftsHeldKeysAndFastForward() {
        val mapper = InputMapper()
        mapper.onKey(KEYCODE_BUTTON_B, down = true)
        mapper.onKey(KEYCODE_DPAD_LEFT, down = true)
        mapper.onKey(KEYCODE_BUTTON_R2, down = true)
        mapper.releaseAll()
        assertEquals(0, mapper.keys())
        assertEquals(false, mapper.fastForward())
    }
}
