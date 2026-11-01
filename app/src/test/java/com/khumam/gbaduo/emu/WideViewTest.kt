package com.khumam.gbaduo.emu

import com.khumam.gbaduo.companion.WideMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun extraWideKeepsTheScreensShapeAndGrowsBothWays() {
        // 1920x1080, whole pixels: 4 screen pixels per game pixel, 480x270.
        assertEquals(WideSize(480, 270), WideView.extraSizeFor(1920, 1080, integer = true))
        // Free scaling: 240 rows, as wide as the screen's shape asks (427 -> 426), within the cap.
        assertEquals(WideSize(426, 240), WideView.extraSizeFor(1920, 1080, integer = false))
    }

    @Test
    fun extraWideStaysWithinTheBufferAndNeverBelowTheFrame() {
        assertEquals(WideSize(480, 240), WideView.extraSizeFor(3840, 1080, integer = false))
        assertEquals(WideSize(240, 160), WideView.extraSizeFor(0, 0, integer = false))
        // A tall screen keeps the 240 columns and grows taller, up to 320 rows.
        assertEquals(WideSize(240, 320), WideView.extraSizeFor(800, 1200, integer = false))
        val s = WideView.extraSizeFor(1927, 1083, integer = true)
        assertEquals(0, s.width % 2)
        assertEquals(0, s.height % 2)
    }

    @Test
    fun wantedSizeByMode() {
        assertEquals(WideView.OFF, WideView.wantedSize(WideMode.OFF, true, 1920, 1080, true))
        assertEquals(WideView.OFF, WideView.wantedSize(WideMode.EXTRA, false, 1920, 1080, true))
        assertEquals(WideSize(320, 160), WideView.wantedSize(WideMode.WIDE, true, 1920, 1080, true))
        assertEquals(WideSize(480, 270), WideView.wantedSize(WideMode.EXTRA, true, 1920, 1080, true))
    }

    @Test
    fun fallsBackAfterAWhileWithoutAWorldAndComesBackWhenItIsBack() {
        val f = WideFallback()
        repeat(WideFallback.UNUSABLE_FRAMES + WideFallback.MIN_DWELL_FRAMES) { f.step(shown = false, coverage = 0f) }
        assertTrue(f.active)
        // A good world, but not for long enough yet to leave the fallback right after entering it.
        var back = false
        repeat(WideFallback.MIN_DWELL_FRAMES) { back = !f.step(shown = true, coverage = 0.9f) }
        assertTrue(back)
        assertFalse(f.active)
    }

    @Test
    fun aShortDipDoesNotFallBack() {
        val f = WideFallback()
        repeat(100) { f.step(shown = true, coverage = 0.9f) }
        repeat(WideFallback.UNUSABLE_FRAMES - 1) { f.step(shown = false, coverage = 0f) }
        f.step(shown = true, coverage = 0.9f)
        repeat(WideFallback.UNUSABLE_FRAMES - 1) { f.step(shown = false, coverage = 0f) }
        assertFalse(f.active)
    }

    @Test
    fun aSmallRoomFallsBackEvenWhileTheMarginsShow() {
        val f = WideFallback()
        repeat(100) { f.step(shown = true, coverage = 0.05f) }
        assertTrue(f.active)
        // Staying in the fallback needs real coverage to come back, not just the margins showing.
        repeat(100) { f.step(shown = true, coverage = 0.2f) }
        assertTrue(f.active)
        repeat(WideFallback.MIN_DWELL_FRAMES) { f.step(shown = true, coverage = 0.5f) }
        assertFalse(f.active)
    }

    @Test
    fun turningWideOffResetsTheFallback() {
        val f = WideFallback()
        repeat(100) { f.step(shown = false, coverage = 0f) }
        assertTrue(f.active)
        f.reset()
        assertFalse(f.active)
    }

    @Test
    fun aClearlyWrongWorldFallsBackWithinAFewFrames() {
        val f = WideFallback()
        repeat(WideFallback.MIN_DWELL_FRAMES) { f.step(shown = true, coverage = 0.9f, match = 0.95f) }
        repeat(WideFallback.CLEARLY_WRONG_FRAMES - 1) { f.step(shown = true, coverage = 0.9f, match = 0.2f) }
        assertFalse(f.active)
        f.step(shown = true, coverage = 0.9f, match = 0.2f)
        assertTrue(f.active)
    }

    @Test
    fun aMatchThatOnlyDipsDoesNotTakeTheFastPath() {
        val f = WideFallback()
        repeat(WideFallback.MIN_DWELL_FRAMES) { f.step(shown = true, coverage = 0.9f, match = 0.95f) }
        repeat(WideFallback.UNUSABLE_FRAMES - 1) { f.step(shown = true, coverage = 0.9f, match = 0.7f) }
        assertFalse(f.active)
    }
}
