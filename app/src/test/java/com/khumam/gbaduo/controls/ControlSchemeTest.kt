package com.khumam.gbaduo.controls

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_BUTTON_B
import android.view.KeyEvent.KEYCODE_BUTTON_L1
import android.view.KeyEvent.KEYCODE_BUTTON_L2
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_R2
import android.view.KeyEvent.KEYCODE_BUTTON_SELECT
import android.view.KeyEvent.KEYCODE_BUTTON_X
import android.view.KeyEvent.KEYCODE_BUTTON_Y
import com.khumam.gbaduo.emu.GbaKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSchemeTest {

    private val default = ControlScheme.DEFAULT

    private fun Change.scheme(): ControlScheme = (this as Change.Done).scheme

    @Test
    fun defaultsMatchSpec() {
        assertEquals(GbaKeys.A, default.gameBitFor(KEYCODE_BUTTON_A))
        assertEquals(GbaKeys.B, default.gameBitFor(KEYCODE_BUTTON_B))
        assertEquals(HotkeyAction.QUICK_MENU, default.singleActionFor(KEYCODE_BUTTON_L2))
        assertEquals(HotkeyAction.FAST_FORWARD, default.singleActionFor(KEYCODE_BUTTON_R2))
        assertNull(default.hotkeyButton)
        assertEquals(2, default.actions.size)
        assertEquals(FastForwardMode.HOLD, default.ffMode)
    }

    @Test
    fun assignFreeKey() {
        val change = default.assignButton(GbaKeys.A, KEYCODE_BUTTON_X) as Change.Done
        assertEquals(KEYCODE_BUTTON_X, change.scheme.buttons[GbaKeys.A])
        assertNull(change.moved)
    }

    @Test
    fun assignUsedKeySwapsButtons() {
        val change = default.assignButton(GbaKeys.A, KEYCODE_BUTTON_B) as Change.Done
        assertEquals(KEYCODE_BUTTON_B, change.scheme.buttons[GbaKeys.A])
        assertEquals(KEYCODE_BUTTON_A, change.scheme.buttons[GbaKeys.B])
        assertEquals(Moved(KEYCODE_BUTTON_A, Target.Button(GbaKeys.B)), change.moved)
    }

    @Test
    fun assignSingleActionKeySwaps() {
        val change = default.assignButton(GbaKeys.L, KEYCODE_BUTTON_L2) as Change.Done
        assertEquals(KEYCODE_BUTTON_L2, change.scheme.buttons[GbaKeys.L])
        assertEquals(Binding.Single(KEYCODE_BUTTON_L1), change.scheme.actions[HotkeyAction.QUICK_MENU])
        assertEquals(Target.Action(HotkeyAction.QUICK_MENU), change.moved!!.to)
    }

    @Test
    fun actionOnButtonKeyWithNothingToGiveRefused() {
        val change = default.assignAction(HotkeyAction.QUICK_SAVE, Binding.Single(KEYCODE_BUTTON_A))
        assertEquals(Change.Refused(Refusal.WOULD_LEAVE_BUTTON_EMPTY), change)
    }

    @Test
    fun actionOnButtonKeySwapsWhenItHasAKey() {
        val s = default.assignAction(HotkeyAction.QUICK_MENU, Binding.Single(KEYCODE_BUTTON_A)).scheme()
        assertEquals(Binding.Single(KEYCODE_BUTTON_A), s.actions[HotkeyAction.QUICK_MENU])
        assertEquals(KEYCODE_BUTTON_L2, s.buttons[GbaKeys.A])
    }

    @Test
    fun singleActionsSwap() {
        val s = default.assignAction(HotkeyAction.QUICK_MENU, Binding.Single(KEYCODE_BUTTON_R2)).scheme()
        assertEquals(Binding.Single(KEYCODE_BUTTON_R2), s.actions[HotkeyAction.QUICK_MENU])
        assertEquals(Binding.Single(KEYCODE_BUTTON_L2), s.actions[HotkeyAction.FAST_FORWARD])
    }

    @Test
    fun withHotkeyReusesKeys() {
        val withHotkey = default.setHotkeyButton(KEYCODE_BUTTON_SELECT).scheme()
        val change = withHotkey.assignAction(HotkeyAction.QUICK_SAVE, Binding.WithHotkey(KEYCODE_BUTTON_R1)) as Change.Done
        assertNull(change.moved)
        assertEquals(KEYCODE_BUTTON_R1, change.scheme.buttons[GbaKeys.R])
    }

    @Test
    fun backNeverAssignable() {
        val refused = Change.Refused(Refusal.BACK_KEY)
        assertEquals(refused, default.assignButton(GbaKeys.A, KEYCODE_BACK))
        assertEquals(refused, default.assignAction(HotkeyAction.EXIT, Binding.Single(KEYCODE_BACK)))
        assertEquals(refused, default.setHotkeyButton(KEYCODE_BACK))
    }

    @Test
    fun hotkeyButtonNotUsableAsActionKey() {
        val s = default.setHotkeyButton(KEYCODE_BUTTON_SELECT).scheme()
        val refused = Change.Refused(Refusal.IS_HOTKEY_BUTTON)
        assertEquals(refused, s.assignAction(HotkeyAction.EXIT, Binding.WithHotkey(KEYCODE_BUTTON_SELECT)))
        assertEquals(refused, s.assignAction(HotkeyAction.EXIT, Binding.Single(KEYCODE_BUTTON_SELECT)))
    }

    @Test
    fun hotkeyOverSingleActionUnassignsIt() {
        assertEquals(listOf(HotkeyAction.QUICK_MENU), default.actionsUnassignedBy(KEYCODE_BUTTON_L2))
        val s = default.setHotkeyButton(KEYCODE_BUTTON_L2).scheme()
        assertEquals(KEYCODE_BUTTON_L2, s.hotkeyButton)
        assertNull(s.actions[HotkeyAction.QUICK_MENU])
    }

    @Test
    fun resetButtonsDropsCollidingAction() {
        var s = default.assignAction(HotkeyAction.QUICK_SAVE, Binding.Single(KEYCODE_BUTTON_X)).scheme()
        s = s.assignButton(GbaKeys.A, KEYCODE_BUTTON_Y).scheme()
        s = s.assignAction(HotkeyAction.QUICK_SAVE, Binding.Single(KEYCODE_BUTTON_A)).scheme()
        val reset = s.withDefaultButtons()
        assertEquals(KEYCODE_BUTTON_A, reset.buttons[GbaKeys.A])
        assertNull(reset.actions[HotkeyAction.QUICK_SAVE])
    }

    @Test
    fun resetHotkeysSkipsKeyTakenByButton() {
        val s = default.assignButton(GbaKeys.L, KEYCODE_BUTTON_L2).scheme().withDefaultHotkeys()
        assertNull(s.actions[HotkeyAction.QUICK_MENU])
        assertEquals(Binding.Single(KEYCODE_BUTTON_R2), s.actions[HotkeyAction.FAST_FORWARD])
        assertEquals(KEYCODE_BUTTON_L2, s.buttons[GbaKeys.L])
    }

    @Test
    fun hotkeyOnGameButtonAllowed() {
        val s = default.setHotkeyButton(KEYCODE_BUTTON_SELECT).scheme()
        assertEquals(KEYCODE_BUTTON_SELECT, s.buttons[GbaKeys.SELECT])
        assertTrue(s.hotkeyButton == KEYCODE_BUTTON_SELECT)
    }
}
