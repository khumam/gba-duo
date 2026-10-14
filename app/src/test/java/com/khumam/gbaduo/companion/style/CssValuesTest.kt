package com.khumam.gbaduo.companion.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CssValuesTest {

    private fun hex(c: Int?) = c?.let { "%08X".format(it) }

    @Test
    fun hexColorsUseCssOrder() {
        assertEquals("FFFF0000", hex(CssValues.color("#f00")))
        assertEquals("88FF0000", hex(CssValues.color("#f008")))
        assertEquals("FF112233", hex(CssValues.color("#112233")))
        // #RRGGBBAA in CSS — not Android's #AARRGGBB.
        assertEquals("80112233", hex(CssValues.color("#11223380")))
        assertNull(CssValues.color("#12345"))
        assertNull(CssValues.color("#ggg"))
    }

    @Test
    fun functionalAndNamedColors() {
        assertEquals("FF0A141E", hex(CssValues.color("rgb(10, 20, 30)")))
        assertEquals("800A141E", hex(CssValues.color("rgba(10,20,30,0.5)")))
        assertEquals("400A141E", hex(CssValues.color("rgb(10 20 30 / 25%)")))
        assertEquals("FFFF0000", hex(CssValues.color("hsl(0, 100%, 50%)")))
        assertEquals("FF00FF00", hex(CssValues.color("hsl(120deg 100% 50%)")))
        assertEquals("FFFFA500", hex(CssValues.color("Orange")))
        assertEquals("00000000", hex(CssValues.color("transparent")))
        assertNull(CssValues.color("rgb(1,2)"))
        assertNull(CssValues.color("notacolor"))
    }

    @Test
    fun androidEightDigitColorsBecomeRgba() {
        assertEquals("rgba(17, 34, 51, 0.502)", CssValues.androidColorToCss("#80112233"))
        assertEquals("FF112233", hex(CssValues.color(CssValues.androidColorToCss("#FF112233"))))
        assertEquals("#123456", CssValues.androidColorToCss("#123456"))
        assertEquals("red", CssValues.androidColorToCss("red"))
    }

    @Test
    fun lengthsAndRelativeUnits() {
        val ctx = LengthContext(fontSize = 10f, rootFontSize = 20f, viewportWidth = 400f, viewportHeight = 200f)
        assertEquals(Length.Px(12f), CssValues.length("12px", ctx))
        assertEquals(Length.Px(12f), CssValues.length("12", ctx))
        assertEquals(Length.Px(15f), CssValues.length("1.5em", ctx))
        assertEquals(Length.Px(40f), CssValues.length("2rem", ctx))
        assertEquals(Length.Px(40f), CssValues.length("10vw", ctx))
        assertEquals(Length.Px(20f), CssValues.length("10vh", ctx))
        assertEquals(Length.Percent(50f), CssValues.length("50%", ctx))
        assertEquals(Length.Auto, CssValues.length("auto", ctx))
        assertNull(CssValues.length("auto", ctx, allowAuto = false))
        assertNull(CssValues.length("10%", ctx, allowPercent = false))
        assertNull(CssValues.length("10furlongs", ctx))
        assertEquals(25f, Length.Percent(50f).resolve(50f))
        assertNull(Length.Percent(50f).resolve(null))
    }

    @Test
    fun timesAnglesAndEasing() {
        assertEquals(300L, CssValues.timeMs("300ms"))
        assertEquals(1500L, CssValues.timeMs("1.5s"))
        assertNull(CssValues.timeMs("300"))
        assertEquals(90f, CssValues.angleDeg("0.25turn")!!, 0.01f)
        assertEquals(Easing.EASE_OUT, CssValues.easing("ease-out"))
        assertEquals(Easing(0.1f, 0.7f, 1f, 0.1f), CssValues.easing("cubic-bezier(0.1, 0.7, 1.0, 0.1)"))
        assertNull(CssValues.easing("cubic-bezier(2, 0, 1, 1)"))
    }

    @Test
    fun linearGradients() {
        val g = CssValues.linearGradient("linear-gradient(90deg, #e63946, #ffb703 80%)")!!
        assertEquals(90f, g.angleDeg)
        assertEquals(listOf(null, 0.8f), g.stops.map { it.position })
        assertEquals(180f, CssValues.linearGradient("linear-gradient(red, blue)")!!.angleDeg)
        assertEquals(90f, CssValues.linearGradient("linear-gradient(to right, red, blue)")!!.angleDeg)
        assertEquals(1 to 1, CssValues.linearGradient("linear-gradient(to bottom right, red, blue)")!!.toCorner)
        assertEquals(3, CssValues.linearGradient("linear-gradient(red, rgba(0,0,0,.5), blue)")!!.stops.size)
        assertNull(CssValues.linearGradient("linear-gradient(red)"))
        assertNull(CssValues.linearGradient("radial-gradient(red, blue)"))
    }

    @Test
    fun shadowsTransformsTransitions() {
        assertEquals(Shadow(0f, 2f, 6f, 0f, 0x80000000.toInt()), CssValues.shadow("0 2px 6px rgba(0,0,0,.5)"))
        assertEquals(Shadow(1f, 1f, 0f, 2f, 0xFFFF0000.toInt()), CssValues.shadow("red 1px 1px 0 2px"))
        assertNull(CssValues.shadow("inset 0 0 2px red"))
        assertNull(CssValues.shadow("1px 1px 1px 1px red", allowSpread = false))

        val t = CssValues.transform("translate(10px, 50%) scale(1.5) rotate(45deg)")!!
        assertEquals(Length.Px(10f), t.translateX)
        assertEquals(Length.Percent(50f), t.translateY)
        assertEquals(1.5f, t.scaleX)
        assertEquals(1.5f, t.scaleY)
        assertEquals(45f, t.rotateDeg)
        assertEquals(Transform.NONE, CssValues.transform("none"))
        assertNull(CssValues.transform("skew(10deg)"))

        val tr = CssValues.transitions("width 300ms ease-out, opacity 1s 200ms")!!
        assertEquals(TransitionSpec("width", 300, Easing.EASE_OUT, 0), tr[0])
        assertEquals(TransitionSpec("opacity", 1000, Easing.EASE, 200), tr[1])
        assertEquals(emptyList<TransitionSpec>(), CssValues.transitions("none"))
    }

    @Test
    fun splittingRespectsFunctionsAndQuotes() {
        assertEquals(listOf("1px", "solid", "rgb(1, 2, 3)"), CssValues.splitSpaces("1px  solid rgb(1, 2, 3)"))
        assertEquals(listOf("\"Press Start\"", "monospace"), CssValues.splitCommas("\"Press Start\", monospace"))
        assertEquals("fonts/a.ttf", CssValues.url("url('fonts/a.ttf')"))
    }
}
