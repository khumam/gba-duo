package com.khumam.gbaduo.controls

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_BUTTON_B
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_SELECT
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCaptureTest {

    @Test
    fun capturesSingle() {
        val capture = KeyCapture(hotkeyButton = null, allowCombo = false)
        assertEquals(KeyCapture.Outcome.Waiting, capture.onKey(KEYCODE_BUTTON_R1, down = true, timeMs = 0))
        assertEquals(KeyCapture.Outcome.Captured(Binding.Single(KEYCODE_BUTTON_R1)), capture.onKey(KEYCODE_BUTTON_R1, down = false, timeMs = 100))
    }

    @Test
    fun capturesCombo() {
        val capture = KeyCapture(hotkeyButton = KEYCODE_BUTTON_SELECT, allowCombo = true)
        capture.onKey(KEYCODE_BUTTON_SELECT, down = true, timeMs = 0)
        capture.onKey(KEYCODE_BUTTON_R1, down = true, timeMs = 50)
        assertEquals(KeyCapture.Outcome.Captured(Binding.WithHotkey(KEYCODE_BUTTON_R1)), capture.onKey(KEYCODE_BUTTON_R1, down = false, timeMs = 100))
    }

    @Test
    fun comboIgnoredForButtons() {
        val capture = KeyCapture(hotkeyButton = KEYCODE_BUTTON_SELECT, allowCombo = false)
        capture.onKey(KEYCODE_BUTTON_SELECT, down = true, timeMs = 0)
        capture.onKey(KEYCODE_BUTTON_R1, down = true, timeMs = 50)
        assertEquals(KeyCapture.Outcome.Captured(Binding.Single(KEYCODE_BUTTON_SELECT)), capture.onKey(KEYCODE_BUTTON_R1, down = false, timeMs = 100))
    }

    @Test
    fun bCanBeCaptured() {
        val capture = KeyCapture(hotkeyButton = null, allowCombo = false)
        capture.onKey(KEYCODE_BUTTON_B, down = true, timeMs = 0)
        assertEquals(KeyCapture.Outcome.Captured(Binding.Single(KEYCODE_BUTTON_B)), capture.onKey(KEYCODE_BUTTON_B, down = false, timeMs = 80))
    }

    @Test
    fun backCancels() {
        val capture = KeyCapture(hotkeyButton = null, allowCombo = false)
        assertEquals(KeyCapture.Outcome.Cancelled, capture.onKey(KEYCODE_BACK, down = true, timeMs = 0))
    }

    @Test
    fun longHoldCancels() {
        val capture = KeyCapture(hotkeyButton = null, allowCombo = false)
        capture.onKey(KEYCODE_BUTTON_R1, down = true, timeMs = 0)
        assertEquals(KeyCapture.Outcome.Waiting, capture.onTick(1999))
        assertEquals(KeyCapture.Outcome.Cancelled, capture.onTick(2000))
    }

    @Test
    fun releaseAfterLongHoldCancelsToo() {
        val capture = KeyCapture(hotkeyButton = null, allowCombo = false)
        capture.onKey(KEYCODE_BUTTON_R1, down = true, timeMs = 0)
        assertEquals(KeyCapture.Outcome.Cancelled, capture.onKey(KEYCODE_BUTTON_R1, down = false, timeMs = 2500))
    }

    @Test
    fun tickWithNothingHeldWaits() {
        assertEquals(KeyCapture.Outcome.Waiting, KeyCapture(null, false).onTick(10_000))
    }
}
