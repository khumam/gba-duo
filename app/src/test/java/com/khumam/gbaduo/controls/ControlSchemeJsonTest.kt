package com.khumam.gbaduo.controls

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_BUTTON_B
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_SELECT
import com.khumam.gbaduo.emu.GbaKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlSchemeJsonTest {

    private val default = ControlScheme.DEFAULT

    @Test
    fun roundTrip() {
        var s = default.assignButton(GbaKeys.A, KEYCODE_BUTTON_B).let { (it as Change.Done).scheme }
        s = (s.setHotkeyButton(KEYCODE_BUTTON_SELECT) as Change.Done).scheme
        s = (s.assignAction(HotkeyAction.QUICK_SAVE, Binding.WithHotkey(KEYCODE_BUTTON_R1)) as Change.Done).scheme
        s = s.withFfMode(FastForwardMode.TOGGLE)
        assertEquals(s, ControlSchemeJson.decode(ControlSchemeJson.encode(s)))
    }

    @Test
    fun nullOrGarbageIsDefault() {
        assertEquals(default, ControlSchemeJson.decode(null))
        assertEquals(default, ControlSchemeJson.decode(""))
        assertEquals(default, ControlSchemeJson.decode("{"))
    }

    private fun json(buttons: String, actions: String, ff: String = "HOLD") =
        """{"version":1,"profiles":{"builtin":{"buttons":$buttons,"hotkey":null,"actions":$actions,"ffMode":"$ff"}}}"""

    private val goodButtons = """{"A":96,"B":97,"L":102,"R":103,"START":108,"SELECT":109,"UP":19,"DOWN":20,"LEFT":21,"RIGHT":22}"""

    @Test
    fun invalidFallsBackPerField() {
        val s = ControlSchemeJson.decode(
            json(
                goodButtons,
                """{"QUICK_MENU":{"key":104,"hotkey":false},"SCREENSHOT":{"key":99,"hotkey":false},"EXIT":{"key":$KEYCODE_BACK,"hotkey":false}}""",
                ff = "WARP",
            ),
        )
        assertEquals(FastForwardMode.HOLD, s.ffMode)
        assertEquals(Binding.Single(104), s.actions[HotkeyAction.QUICK_MENU])
        assertNull(s.actions[HotkeyAction.EXIT])
        assertEquals(1, s.actions.size)
    }

    @Test
    fun brokenButtonsFallBackToDefaults() {
        val missingB = """{"A":96,"L":102,"R":103,"START":108,"SELECT":109,"UP":19,"DOWN":20,"LEFT":21,"RIGHT":22}"""
        assertEquals(default.buttons, ControlSchemeJson.decode(json(missingB, "{}")).buttons)
        val dupes = """{"A":96,"B":96,"L":102,"R":103,"START":108,"SELECT":109,"UP":19,"DOWN":20,"LEFT":21,"RIGHT":22}"""
        assertEquals(default.buttons, ControlSchemeJson.decode(json(dupes, "{}")).buttons)
    }

    @Test
    fun unknownProfilesIgnored() {
        val text = """{"version":1,"profiles":{"ext:1234":{"buttons":{}},"builtin":{"buttons":$goodButtons,"hotkey":null,"actions":{},"ffMode":"HOLD"}}}"""
        val s = ControlSchemeJson.decode(text)
        assertEquals(default.buttons, s.buttons)
        assertEquals(0, s.actions.size)
    }

    @Test
    fun actionClashingWithGameButtonIsDropped() {
        val s = ControlSchemeJson.decode(json(goodButtons, """{"EXIT":{"key":$KEYCODE_BUTTON_A,"hotkey":false}}"""))
        assertNull(s.actions[HotkeyAction.EXIT])
    }
}
