package com.khumam.gbaduo.companion.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropertiesTest {

    private fun expand(p: String, v: String) = Properties.expand(p, v)?.toMap()

    @Test
    fun boxShorthands() {
        assertEquals(mapOf("margin-top" to "1px", "margin-right" to "2px", "margin-bottom" to "1px", "margin-left" to "2px"), expand("margin", "1px 2px"))
        assertEquals("auto", expand("margin", "0 auto")!!["margin-left"])
        assertEquals("3px", expand("padding", "1px 2px 3px")!!["padding-bottom"])
        assertNull(expand("padding", "1px 2px 3px 4px 5px"))
        assertNull(expand("padding", "auto")) // padding can't be auto
    }

    @Test
    fun borderShorthandsInAnyOrder() {
        val b = expand("border", "#f00 2px dashed")!!
        assertEquals("2px", b["border-left-width"])
        assertEquals("dashed", b["border-top-style"])
        assertEquals("#f00", b["border-bottom-color"])
        assertEquals(3, expand("border-top", "1px solid red")!!.size)
        assertEquals("0", expand("border", "none")!!["border-top-width"])
        assertEquals("4px", expand("border-radius", "8px 4px")!!["border-top-right-radius"])
        assertEquals("50%", expand("border-radius", "50%")!!["border-bottom-left-radius"])
    }

    @Test
    fun backgroundShorthand() {
        val bg = expand("background", "url(img/bg.png) center / cover no-repeat #000")!!
        assertEquals("url(img/bg.png)", bg["background-image"])
        assertEquals("cover", bg["background-size"])
        assertEquals("no-repeat", bg["background-repeat"])
        assertEquals("#000", bg["background-color"])
        assertEquals("center", bg["background-position"])
        assertEquals("linear-gradient(90deg, red, blue)", expand("background", "linear-gradient(90deg, red, blue)")!!["background-image"])
        assertEquals("#123", expand("background", "#123")!!["background-color"])
    }

    @Test
    fun flexShorthand() {
        assertEquals(mapOf("flex-grow" to "1", "flex-shrink" to "1", "flex-basis" to "0%"), expand("flex", "1"))
        assertEquals(mapOf("flex-grow" to "2", "flex-shrink" to "0", "flex-basis" to "100px"), expand("flex", "2 0 100px"))
        assertEquals(mapOf("flex-grow" to "0", "flex-shrink" to "0", "flex-basis" to "auto"), expand("flex", "none"))
        assertEquals("auto", expand("flex", "1 1 auto")!!["flex-basis"])
        assertEquals(mapOf("flex-direction" to "column", "flex-wrap" to "wrap"), expand("flex-flow", "column wrap"))
    }

    @Test
    fun gapAndPlaceShorthands() {
        assertEquals(mapOf("row-gap" to "8px", "column-gap" to "8px"), expand("gap", "8px"))
        assertEquals(mapOf("row-gap" to "4px", "column-gap" to "12px"), expand("gap", "4px 12px"))
        assertEquals(mapOf("align-items" to "center", "justify-items" to "center"), expand("place-items", "center"))
    }

    @Test
    fun keywordsApplyToEveryLonghand() {
        assertEquals(setOf("inherit"), expand("margin", "inherit")!!.values.toSet())
        assertEquals(4, expand("margin", "inherit")!!.size)
    }

    @Test
    fun gridTracksAndPlacement() {
        assertEquals(3, GridTracks.parse("repeat(3, 1fr)")!!.size)
        assertEquals(listOf(GridTracks.Track.Fixed(Length.Px(100f)), GridTracks.Track.Fr(2f), GridTracks.Track.Auto), GridTracks.parse("100px 2fr auto"))
        assertEquals(GridTracks.Track.MinMax(GridTracks.Track.Fixed(Length.Px(0f)), GridTracks.Track.Fr(1f)), GridTracks.parse("minmax(0, 1fr)")!!.single())
        assertEquals(emptyList<GridTracks.Track>(), GridTracks.parse("none"))
        assertEquals(GridTracks.Track.AutoRepeat(listOf(GridTracks.Track.Fixed(Length.Px(100f))), fit = false), GridTracks.parse("repeat(auto-fill, 100px)")!!.single())
        assertEquals(GridTracks.Placement(null, 2), GridTracks.placement("span 2"))
        assertEquals(GridTracks.Placement(2, 1), GridTracks.placement("2"))
        assertEquals(GridTracks.Placement(1, 3), GridTracks.placement("1 / 4"))
        assertEquals(GridTracks.Placement(2, 2), GridTracks.placement("2 / span 2"))
        assertNull(GridTracks.placement("span 0"))
    }

    @Test
    fun fontHelpers() {
        assertEquals(21f, Properties.fontSizePx("1.5em", 14f, 14f, 0f, 0f)!!, 0.01f)
        assertEquals(7f, Properties.fontSizePx("50%", 14f, 14f, 0f, 0f)!!, 0.01f)
        assertEquals(18f, Properties.fontSizePx("large", 14f, 14f, 0f, 0f)!!, 0.01f)
        assertEquals(700, Properties.fontWeight("bold", 400))
        assertEquals(700, Properties.fontWeight("bolder", 400))
        assertEquals(100, Properties.fontWeight("lighter", 400))
        assertEquals(0.5f to 1f, Properties.backgroundPosition("center bottom"))
        assertEquals(0.25f to 0.75f, Properties.backgroundPosition("25% 75%"))
        assertEquals(16f / 9f, Properties.aspectRatio("16 / 9")!!, 0.001f)
    }
}
