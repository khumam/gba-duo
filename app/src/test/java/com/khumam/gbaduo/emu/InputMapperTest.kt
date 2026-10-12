package com.khumam.gbaduo.emu

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class InputMapperTest {

    @Test
    fun mapsFaceButtonsToAAndB() {
        val mapper = InputMapper()
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_B, down = true)
        assertEquals(GbaKeys.A, mapper.keys())

        mapper.onKey(KeyEvent.KEYCODE_BUTTON_B, down = false)
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true)
        assertEquals(GbaKeys.B, mapper.keys())
    }

    @Test
    fun swapAbInvertsFaceButtons() {
        val mapper = InputMapper()
        mapper.swapAb = true
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_B, down = true)
        assertEquals(GbaKeys.B, mapper.keys())
    }

    @Test
    fun mapsShouldersStartSelectAndDpad() {
        val mapper = InputMapper()
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_L1, down = true)
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_R1, down = true)
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_START, down = true)
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_SELECT, down = true)
        mapper.onKey(KeyEvent.KEYCODE_DPAD_UP, down = true)
        assertEquals(GbaKeys.L or GbaKeys.R or GbaKeys.START or GbaKeys.SELECT or GbaKeys.UP, mapper.keys())
    }

    @Test
    fun releasingAKeyClearsItsBit() {
        val mapper = InputMapper()
        mapper.onKey(KeyEvent.KEYCODE_DPAD_LEFT, down = true)
        mapper.onKey(KeyEvent.KEYCODE_DPAD_LEFT, down = false)
        assertEquals(0, mapper.keys())
    }

    @Test
    fun unmappedKeyIsIgnored() {
        val mapper = InputMapper()
        mapper.onKey(KeyEvent.KEYCODE_VOLUME_UP, down = true)
        assertEquals(0, mapper.keys())
    }

    @Test
    fun r2HeldReportsFastForward() {
        val mapper = InputMapper()
        assertEquals(false, mapper.fastForward())
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_R2, down = true)
        assertEquals(true, mapper.fastForward())
        mapper.onKey(KeyEvent.KEYCODE_BUTTON_R2, down = false)
        assertEquals(false, mapper.fastForward())
    }
}
