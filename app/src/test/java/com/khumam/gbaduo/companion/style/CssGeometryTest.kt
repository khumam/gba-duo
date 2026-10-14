package com.khumam.gbaduo.companion.style

import com.khumam.gbaduo.companion.layout.BoxStyle
import com.khumam.gbaduo.companion.layout.ContainerStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CssGeometryTest {

    private fun line(css: String, w: Float, h: Float) = CssGeometry.gradientLine(CssValues.linearGradient(css)!!, w, h)

    @Test
    fun gradientLinesFollowCssAngles() {
        assertArrayEquals(floatArrayOf(50f, 0f, 50f, 100f), line("linear-gradient(red, blue)", 100f, 100f), 0.01f)       // default: to bottom
        assertArrayEquals(floatArrayOf(0f, 25f, 200f, 25f), line("linear-gradient(90deg, red, blue)", 200f, 50f), 0.01f) // to right
        assertArrayEquals(floatArrayOf(50f, 100f, 50f, 0f), line("linear-gradient(to top, red, blue)", 100f, 100f), 0.01f)
        // 45deg in a square: corner to corner.
        assertArrayEquals(floatArrayOf(0f, 100f, 100f, 0f), line("linear-gradient(45deg, red, blue)", 100f, 100f), 0.01f)
    }

    @Test
    fun cornerGradientIsPerpendicularToTheOtherDiagonal() {
        // In a 200x100 box, "to bottom right" points along (h, w) = (100, 200).
        val l = line("linear-gradient(to bottom right, red, blue)", 200f, 100f)
        val dx = l[2] - l[0]
        val dy = l[3] - l[1]
        assertEquals(100f / 200f, dx / dy, 0.01f)
        assertEquals(100f, (l[0] + l[2]) / 2, 0.01f) // centered
    }

    @Test
    fun stopPositionsAreFilledInLikeCss() {
        fun stops(vararg p: Float?) = p.map { GradientStop(0, it) }
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), CssGeometry.stopPositions(stops(null, null, null)), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0.2f, 0.6f, 1f), CssGeometry.stopPositions(stops(null, 0.2f, null, null)), 0.001f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), CssGeometry.stopPositions(stops(0.5f, 0.3f)), 0.001f) // never decreasing
    }

    @Test
    fun radiiShrinkTogetherWhenTheyOverflow() {
        assertArrayEquals(floatArrayOf(10f, 10f, 10f, 10f), CssGeometry.fitRadii(floatArrayOf(10f, 10f, 10f, 10f), 100f, 100f), 0.001f)
        // 999px pill on a 100x20 box → radius 10 (half the height).
        assertArrayEquals(floatArrayOf(10f, 10f, 10f, 10f), CssGeometry.fitRadii(floatArrayOf(999f, 999f, 999f, 999f), 100f, 20f), 0.001f)
    }

    @Test
    fun scalingToDevicePixelsLeavesPercentagesAlone() {
        val box = BoxStyle(width = Length.Px(10f), height = Length.Percent(50f), marginLeft = Length.Auto, extraWidth = 2f).scaled(3f)
        assertEquals(Length.Px(30f), box.width)
        assertEquals(Length.Percent(50f), box.height)
        assertEquals(Length.Auto, box.marginLeft)
        assertEquals(6f, box.extraWidth)
        val c = ContainerStyle(rowGap = 4f, columns = GridTracks.parse("10px 1fr 20%")!!).scaled(2f)
        assertEquals(8f, c.rowGap)
        assertEquals(listOf(GridTracks.Track.Fixed(Length.Px(20f)), GridTracks.Track.Fr(1f), GridTracks.Track.Fixed(Length.Percent(20f))), c.columns)
    }
}
