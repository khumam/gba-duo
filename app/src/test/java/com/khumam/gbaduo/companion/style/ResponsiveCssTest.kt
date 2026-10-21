package com.khumam.gbaduo.companion.style

import com.khumam.gbaduo.companion.layout.BoxStyle
import com.khumam.gbaduo.companion.layout.Constraint
import com.khumam.gbaduo.companion.layout.ContainerStyle
import com.khumam.gbaduo.companion.layout.LayoutChild
import com.khumam.gbaduo.companion.layout.LayoutEngine
import com.khumam.gbaduo.companion.layout.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `@media`, `calc()`/`min()`/`max()`/`clamp()`, and grid `auto-fill`/`auto-fit`/`minmax()`. */
class ResponsiveCssTest {

    // ---- @media -------------------------------------------------------------------------

    private fun mq(text: String) = MediaQuery.parse(text)

    @Test
    fun mediaFeatures() {
        assertTrue(mq("(min-width: 500px)")!!.matches(538f, 468f))
        assertFalse(mq("(min-width: 600px)")!!.matches(538f, 468f))
        assertTrue(mq("(max-width: 538px)")!!.matches(538f, 468f))
        assertTrue(mq("(orientation: landscape)")!!.matches(538f, 468f))
        assertFalse(mq("(orientation: landscape)")!!.matches(360f, 640f))
        assertTrue(mq("(orientation: portrait)")!!.matches(360f, 640f))
        assertTrue(mq("(min-aspect-ratio: 1/1)")!!.matches(538f, 468f))
        assertTrue(mq("(max-aspect-ratio: 16/9)")!!.matches(538f, 468f))
        assertTrue(mq("screen and (min-width: 300px) and (max-height: 500px)")!!.matches(538f, 468f))
        assertFalse(mq("print")!!.matches(538f, 468f))
        assertTrue(mq("not print")!!.matches(538f, 468f))
        assertTrue(mq("(max-width: 100px), (min-height: 400px)")!!.matches(538f, 468f)) // comma = or
        assertTrue(mq("(min-width: 30em)")!!.matches(538f, 468f)) // 30 x 14px = 420px
    }

    @Test
    fun mediaRangeSyntax() {
        assertTrue(mq("(width >= 500px)")!!.matches(538f, 468f))
        assertFalse(mq("(width < 500px)")!!.matches(538f, 468f))
        assertTrue(mq("(400px <= width < 600px)")!!.matches(538f, 468f))
        assertFalse(mq("(400px <= width < 500px)")!!.matches(538f, 468f))
        assertTrue(mq("(height > 400px)")!!.matches(538f, 468f))
    }

    @Test
    fun unknownMediaIsRejected() {
        assertNull(mq("(min-resolution: 2dppx)"))
        assertNull(mq("(hover: hover)"))
        assertNull(mq("tv"))
        assertNull(mq("(min-width: 50%)"))
        assertNull(mq("(min-width: 400px) or (max-width: 100px)"))
    }

    @Test
    fun mediaRulesApplyByScreenSizeAndFollowResizes() {
        val sheet = CssParser.parse(
            """
            .card { width: 100px; }
            @media (max-width: 400px) { .card { width: 50px; } }
            @media (min-width: 500px) and (orientation: landscape) {
              .card { height: 30px; }
              @media (min-width: 9999px) { .card { height: 1px; } }
            }
            """.trimIndent(),
        )
        assertTrue(sheet.warnings.toString(), sheet.warnings.isEmpty())
        val resolver = StyleResolver(emptyList(), listOf(sheet), 538f, 468f)
        val node = StyleNode("box", staticClasses = setOf("card"))

        val wide = resolver.resolve(node, null)
        assertEquals("100px", wide.raw("width"))
        assertEquals("30px", wide.raw("height")) // the nested block doesn't match

        resolver.viewportWidth = 360f
        resolver.viewportHeight = 640f
        val narrow = resolver.resolve(node, null)
        assertEquals("50px", narrow.raw("width"))
        assertEquals("auto", narrow.raw("height"))
    }

    @Test
    fun unsupportedMediaBlockIsSkippedWithAWarningAndTheRestStillParses() {
        val sheet = CssParser.parse("@media (hover: hover) { .a { width: 1px } } .b { width: 2px }")
        assertEquals(1, sheet.rules.size)
        assertTrue(sheet.warnings.single().message.contains("media query"))
    }

    // ---- calc(), min(), max(), clamp() ----------------------------------------------------

    private val ctx = LengthContext(fontSize = 10f, rootFontSize = 14f, viewportWidth = 500f, viewportHeight = 400f)

    private fun len(text: String, allowPercent: Boolean = true) = CssValues.length(text, ctx, allowPercent = allowPercent)

    @Test
    fun calcResolvesUnitsRightAway() {
        assertEquals(Length.Px(30f), len("calc(10px + 2em)"))
        assertEquals(Length.Px(66f), len("calc(10vw + 16px)"))
        assertEquals(Length.Px(15f), len("calc((10px + 20px) / 2)"))
        assertEquals(Length.Px(40f), len("calc(2 * 20px)"))
        assertEquals(Length.Px(-4f), len("calc(1rem - 18px)"))
        assertEquals(Length.Px(9f), len("calc(3px * 3)"))
    }

    @Test
    fun calcWithPercentStaysForLayout() {
        assertEquals(Length.Calc(-20f, 100f), len("calc(100% - 20px)"))
        assertEquals(Length.Percent(50f), len("calc(100% / 2)"))
        assertEquals(120f, len("calc(50% + 20px)")!!.resolve(200f)!!, 0.001f)
        assertNull(len("calc(100% - 20px)", allowPercent = false))
    }

    @Test
    fun minMaxClamp() {
        assertEquals(Length.Px(12f), len("min(12px, 5vw)"))   // 5vw = 25px
        assertEquals(Length.Px(25f), len("max(12px, 5vw)"))
        assertEquals(Length.Px(18f), len("clamp(12px, 5vw, 18px)"))
        assertEquals(Length.Px(12f), len("clamp(12px, 1vw, 18px)"))
        assertEquals(Length.Px(15f), len("clamp(12px, 3vw, 18px)"))
        assertEquals(Length.Percent(50f), len("min(50%, 80%)"))
        assertEquals(Length.Px(20f), len("calc(max(10px, 2em) * 1)"))
    }

    @Test
    fun invalidMathIsRefused() {
        assertNull(len("calc(100%-20px)"))       // + and - need spaces
        assertNull(len("calc(10px * 2px)"))
        assertNull(len("calc(10px / 0)"))
        assertNull(len("calc(10px + 2)"))        // length + number
        assertNull(len("min(50%, 10px)"))        // can't compare % and px yet
        assertNull(len("clamp(1px, 2px)"))
        assertNull(len("calc(10px"))
        assertNull(len("calc(10foo)"))
        assertNull(len("calc()"))
    }

    @Test
    fun mathInDeclarationsAndSigns() {
        val sheet = CssParser.parse(".a { width: calc(100% - 24px); padding: clamp(4px, 2vw, 12px); font-size: clamp(12px, 3vw, 20px); gap: calc(5vw - 30px) }")
        assertTrue(sheet.warnings.toString(), sheet.warnings.isEmpty())
        val resolver = StyleResolver(emptyList(), listOf(sheet), 500f, 400f)
        val style = resolver.resolve(StyleNode("box", staticClasses = setOf("a")), null)
        assertEquals(Length.Calc(-24f, 100f), style.length("width"))
        assertEquals(10f, style.padding("left"), 0.001f)       // 2vw = 10px
        assertEquals(15f, style.fontSizePx, 0.001f)            // 3vw = 15px
        // 5vw - 30px = -5px: accepted, then clamped to 0 where a negative makes no sense.
        assertEquals(0f, ContainerStyle.from(style).rowGap, 0.001f)
        // The same rule on a wider screen.
        resolver.viewportWidth = 800f
        val wide = resolver.resolve(StyleNode("box", staticClasses = setOf("a")), null)
        assertEquals(12f, wide.padding("left"), 0.001f)
        assertEquals(20f, wide.fontSizePx, 0.001f)
        assertEquals(10f, ContainerStyle.from(wide).rowGap, 0.001f)
    }

    @Test
    fun calcWidthLaysOutAgainstTheContainer() {
        val sheet = CssParser.parse(".a { width: calc(100% - 40px); margin-left: calc(10% + 4px) }")
        val style = StyleResolver(emptyList(), listOf(sheet)).resolve(StyleNode("box", staticClasses = setOf("a")), null)
        val child = Child(10f, 10f, BoxStyle.from(style))
        val r = LayoutEngine.layout(ContainerStyle(), Constraint.Exactly(300f), Constraint.Exactly(100f), listOf(child)).rects[0]!!
        assertEquals(260f, r.width, 0.01f)
        assertEquals(34f, r.x, 0.01f)
    }

    // ---- grid ------------------------------------------------------------------------------

    private class Child(val w: Float, val h: Float, override val style: BoxStyle = BoxStyle()) : LayoutChild {
        override fun measure(width: Constraint, height: Constraint) = Size(
            when (width) { is Constraint.Exactly -> width.px; is Constraint.AtMost -> minOf(width.px, w); Constraint.Unbounded -> w },
            when (height) { is Constraint.Exactly -> height.px; is Constraint.AtMost -> minOf(height.px, h); Constraint.Unbounded -> h },
        )
    }

    private fun grid(columns: String, gap: Float = 10f) = ContainerStyle(
        display = com.khumam.gbaduo.companion.layout.Display.GRID,
        columns = GridTracks.parse(columns)!!,
        columnGap = gap,
        rowGap = gap,
    )

    private fun xs(columns: String, width: Float, items: Int, gap: Float = 10f): List<Float> =
        LayoutEngine.layout(grid(columns, gap), Constraint.Exactly(width), Constraint.Unbounded, List(items) { Child(10f, 10f) })
            .rects.map { it!!.x }

    @Test
    fun autoFillFitsAsManyColumnsAsTheWidthAllows() {
        // 100px columns + 10px gaps: 538px fits 4 (4*100 + 3*10 = 430), 360px fits 3.
        val wide = LayoutEngine.layout(grid("repeat(auto-fill, minmax(100px, 1fr))"), Constraint.Exactly(538f), Constraint.Unbounded, List(5) { Child(10f, 10f) })
        assertEquals(4, wide.rects.map { it!!.x }.distinct().size)
        assertEquals((538f - 30f) / 4f, wide.rects[0]!!.width, 0.01f) // 1fr shares the rest
        val narrow = LayoutEngine.layout(grid("repeat(auto-fill, minmax(100px, 1fr))"), Constraint.Exactly(360f), Constraint.Unbounded, List(5) { Child(10f, 10f) })
        assertEquals(3, narrow.rects.map { it!!.x }.distinct().size)
        // Always at least one column, even when one doesn't fit.
        assertEquals(1, xs("repeat(auto-fill, 200px)", 120f, 3).distinct().size)
    }

    @Test
    fun autoFillKeepsEmptyTracksButAutoFitDropsThem() {
        val fill = LayoutEngine.layout(grid("repeat(auto-fill, minmax(100px, 1fr))"), Constraint.Exactly(538f), Constraint.Unbounded, List(2) { Child(10f, 10f) })
        assertEquals((538f - 30f) / 4f, fill.rects[0]!!.width, 0.01f) // 4 tracks, 2 empty
        val fit = LayoutEngine.layout(grid("repeat(auto-fit, minmax(100px, 1fr))"), Constraint.Exactly(538f), Constraint.Unbounded, List(2) { Child(10f, 10f) })
        assertEquals((538f - 10f) / 2f, fit.rects[0]!!.width, 0.01f)  // the 2 used tracks share the row
    }

    @Test
    fun minmaxFloorWins() {
        // 1fr would be 45px each; the 60px floor wins and the grid overflows rather than shrinking.
        val r = LayoutEngine.layout(grid("minmax(60px, 1fr) 1fr", gap = 0f), Constraint.Exactly(90f), Constraint.Unbounded, List(2) { Child(10f, 10f) })
        assertEquals(60f, r.rects[0]!!.width, 0.01f)
        assertEquals(30f, r.rects[1]!!.width, 0.01f)
        // Plenty of room: shared evenly.
        val roomy = LayoutEngine.layout(grid("minmax(60px, 1fr) 1fr", gap = 0f), Constraint.Exactly(300f), Constraint.Unbounded, List(2) { Child(10f, 10f) })
        assertEquals(150f, roomy.rects[0]!!.width, 0.01f)
        // Fixed max.
        val fixed = LayoutEngine.layout(grid("minmax(20px, 80px) 1fr", gap = 0f), Constraint.Exactly(300f), Constraint.Unbounded, List(2) { Child(10f, 10f) })
        assertEquals(80f, fixed.rects[0]!!.width, 0.01f)
    }

    @Test
    fun gridTrackParsing() {
        assertEquals(
            listOf(GridTracks.Track.MinMax(GridTracks.Track.Fixed(Length.Px(100f)), GridTracks.Track.Fr(1f))),
            GridTracks.parse("minmax(100px, 1fr)"),
        )
        assertTrue(GridTracks.parse("repeat(auto-fill, 120px)")!!.single() is GridTracks.Track.AutoRepeat)
        assertNull(GridTracks.parse("repeat(auto-fill, 1fr)"))                 // needs a fixed size
        assertNull(GridTracks.parse("repeat(auto-fill, 10px) repeat(auto-fit, 10px)")) // only one
        assertNull(GridTracks.parse("minmax(1fr, 100px)"))                     // flexible minimum
        assertEquals(3, GridTracks.parse("repeat(3, 1fr)")!!.size)            // unchanged
    }

    @Test
    fun minmaxRowsStayFlexible() {
        // `grid-template-rows: minmax(0, 1fr) minmax(0, 1fr)` splits a definite height, as `1fr 1fr` did.
        val style = ContainerStyle(
            display = com.khumam.gbaduo.companion.layout.Display.GRID,
            rows = GridTracks.parse("minmax(0, 1fr) minmax(0, 1fr)")!!,
        )
        val r = LayoutEngine.layout(style, Constraint.Exactly(100f), Constraint.Exactly(200f), List(2) { Child(10f, 10f) })
        assertEquals(100f, r.rects[0]!!.height, 0.01f)
        assertEquals(100f, r.rects[1]!!.y, 0.01f)
    }
}
