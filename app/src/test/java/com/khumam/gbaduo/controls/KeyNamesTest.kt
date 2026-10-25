package com.khumam.gbaduo.controls

import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_SELECT
import android.view.KeyEvent.KEYCODE_BUTTON_THUMBL
import android.view.KeyEvent.KEYCODE_BUTTON_Y
import android.view.KeyEvent.KEYCODE_DPAD_UP
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyNamesTest {
    @Test
    fun names() {
        assertEquals("L3", KeyNames.name(KEYCODE_BUTTON_THUMBL))
        assertEquals("Y", KeyNames.name(KEYCODE_BUTTON_Y))
        assertEquals("Up", KeyNames.name(KEYCODE_DPAD_UP))
        assertEquals("Select", KeyNames.name(KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun describeBindings() {
        assertEquals("Select + R1", KeyNames.describe(Binding.WithHotkey(KEYCODE_BUTTON_R1), KEYCODE_BUTTON_SELECT, "Not set"))
        assertEquals("R1", KeyNames.describe(Binding.Single(KEYCODE_BUTTON_R1), null, "Not set"))
        assertEquals("Not set", KeyNames.describe(null, null, "Not set"))
    }
}
