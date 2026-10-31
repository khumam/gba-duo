package com.khumam.gbaduo.emu

import org.junit.Assert.assertEquals
import org.junit.Test

class WideViewTest {

    @Test
    fun thorMainScreen() {
        assertEquals(320, WideView.widthFor(1920, 1080, integer = true))
        assertEquals(284, WideView.widthFor(1920, 1080, integer = false))
    }

    @Test
    fun thorSecondScreenStaysNarrow() {
        assertEquals(240, WideView.widthFor(1240, 1080, integer = true))
    }

    @Test
    fun widthFor_clamps() {
        assertEquals(400, WideView.widthFor(2400, 1080, integer = true))
        assertEquals(240, WideView.widthFor(0, 0, integer = true))
        assertEquals(240, WideView.widthFor(800, 1200, integer = false))
    }

    @Test
    fun evenWidth() {
        assertEquals(0, WideView.widthFor(1922, 1080, integer = false) % 2)
        assertEquals(0, WideView.widthFor(1927, 1080, integer = true) % 2)
    }

    @Test
    fun wantedWidthIsTheGamesOwnUnlessOnAndAvailable() {
        assertEquals(240, WideView.wantedWidth(enabled = false, hasWideHook = true, surfaceW = 1920, surfaceH = 1080, integer = true))
        assertEquals(240, WideView.wantedWidth(enabled = true, hasWideHook = false, surfaceW = 1920, surfaceH = 1080, integer = true))
        assertEquals(320, WideView.wantedWidth(enabled = true, hasWideHook = true, surfaceW = 1920, surfaceH = 1080, integer = true))
    }
}
