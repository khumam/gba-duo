package com.khumam.gbaduo.emu

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFitTest {

    private fun fit(w: Int, h: Int, vw: Int, vh: Int, aspect: AspectRatio, integer: Boolean) =
        ScreenFit.viewport(w, h, vw, vh, aspect, integer)

    @Test
    fun byIdFindsAspectAndFallsBackToOriginal() {
        assertEquals(AspectRatio.FOUR_THREE, AspectRatio.byId("4_3"))
        assertEquals(AspectRatio.SIXTEEN_NINE, AspectRatio.byId("16_9"))
        assertEquals(AspectRatio.STRETCH, AspectRatio.byId("stretch"))
        assertEquals(AspectRatio.ORIGINAL, AspectRatio.byId("nonsense"))
        assertEquals(AspectRatio.ORIGINAL, AspectRatio.byId(null))
    }

    @Test
    fun gbaOnTopScreenWithoutIntegerScaling() {
        assertEquals(ViewportSize(1620, 1080), fit(1920, 1080, 240, 160, AspectRatio.ORIGINAL, false))
        assertEquals(ViewportSize(1440, 1080), fit(1920, 1080, 240, 160, AspectRatio.FOUR_THREE, false))
        assertEquals(ViewportSize(1920, 1080), fit(1920, 1080, 240, 160, AspectRatio.SIXTEEN_NINE, false))
        assertEquals(ViewportSize(1920, 1080), fit(1920, 1080, 240, 160, AspectRatio.STRETCH, false))
    }

    @Test
    fun gbOnTopScreenWithoutIntegerScaling() {
        assertEquals(ViewportSize(1200, 1080), fit(1920, 1080, 160, 144, AspectRatio.ORIGINAL, false))
        assertEquals(ViewportSize(1440, 1080), fit(1920, 1080, 160, 144, AspectRatio.FOUR_THREE, false))
        assertEquals(ViewportSize(1920, 1080), fit(1920, 1080, 160, 144, AspectRatio.SIXTEEN_NINE, false))
        assertEquals(ViewportSize(1920, 1080), fit(1920, 1080, 160, 144, AspectRatio.STRETCH, false))
    }

    @Test
    fun integerScalingOriginalKeepsSquareWholePixels() {
        assertEquals(ViewportSize(1440, 960), fit(1920, 1080, 240, 160, AspectRatio.ORIGINAL, true))
        assertEquals(ViewportSize(1120, 1008), fit(1920, 1080, 160, 144, AspectRatio.ORIGINAL, true))
    }

    @Test
    fun integerScalingFloorsEachAxisOnItsOwn() {
        assertEquals(ViewportSize(1440, 960), fit(1920, 1080, 240, 160, AspectRatio.FOUR_THREE, true))
        assertEquals(ViewportSize(1920, 960), fit(1920, 1080, 240, 160, AspectRatio.SIXTEEN_NINE, true))
    }

    @Test
    fun stretchWithIntegerScalingRoundsEachAxisDownOnItsOwn() {
        assertEquals(ViewportSize(1920, 960), fit(1920, 1080, 240, 160, AspectRatio.STRETCH, true))
        assertEquals(ViewportSize(1200, 960), fit(1240, 1080, 240, 160, AspectRatio.STRETCH, true))
    }

    @Test
    fun stretchFillsTheNarrowScreenToo() {
        assertEquals(ViewportSize(1240, 1080), fit(1240, 1080, 240, 160, AspectRatio.STRETCH, false))
    }

    @Test
    fun prescaleIsTheSmallestWholeFactorThatCoversTheViewport() {
        assertEquals(8, ScreenFit.prescale(ViewportSize(1920, 1080), 240, 160))
        assertEquals(7, ScreenFit.prescale(ViewportSize(1240, 1080), 240, 160))
        assertEquals(12, ScreenFit.prescale(ViewportSize(1920, 1080), 160, 144))
        assertEquals(1, ScreenFit.prescale(ViewportSize(100, 80), 240, 160))
    }

    @Test
    fun wholeNumberViewportNeedsNoSharpening() {
        assertEquals(true, ScreenFit.isWholeNumber(ViewportSize(1440, 960), 240, 160))
        assertEquals(false, ScreenFit.isWholeNumber(ViewportSize(1620, 1080), 240, 160))
        assertEquals(true, ScreenFit.isWholeNumber(ViewportSize(1920, 960), 240, 160))
        assertEquals(false, ScreenFit.isWholeNumber(ViewportSize(1920, 1080), 240, 160))
    }

    @Test
    fun exactFitIsTheSameWithOrWithoutIntegerScaling() {
        assertEquals(ViewportSize(960, 640), fit(960, 640, 240, 160, AspectRatio.ORIGINAL, false))
        assertEquals(ViewportSize(960, 640), fit(960, 640, 240, 160, AspectRatio.ORIGINAL, true))
    }

    @Test
    fun narrowSurfaceLeavesBarsAboveAndBelow() {
        assertEquals(ViewportSize(1240, 698), fit(1240, 1080, 240, 160, AspectRatio.SIXTEEN_NINE, false))
    }

    @Test
    fun integerScalingNeverGoesBelowTheVideoSize() {
        assertEquals(ViewportSize(240, 160), fit(200, 100, 240, 160, AspectRatio.ORIGINAL, true))
    }
}
