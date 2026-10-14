package com.khumam.gbaduo.companion.layout

import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.GridTracks
import com.khumam.gbaduo.companion.style.Length
import com.khumam.gbaduo.companion.style.StyleNode
import com.khumam.gbaduo.companion.style.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ceil

class LayoutEngineTest {

    /** A box with an intrinsic size that respects constraints like a View would. */
    private class Fixed(val w: Float, val h: Float, override val style: BoxStyle = BoxStyle()) : LayoutChild {
        override fun measure(width: Constraint, height: Constraint) = Size(fit(width, w), fit(height, h))
    }

    /** Text-like: [chars] glyphs of 10px wide, 20px lines; wraps to the available width. */
    private class Text(val chars: Int, override val style: BoxStyle = BoxStyle()) : LayoutChild {
        override fun measure(width: Constraint, height: Constraint): Size {
            val natural = chars * 10f
            val w = fit(width, natural)
            val lines = if (w <= 0f) chars.toFloat() else ceil(natural / w).coerceAtLeast(1f)
            return Size(w, fit(height, lines * 20f))
        }
    }

    companion object {
        fun fit(c: Constraint, natural: Float): Float = when (c) {
            is Constraint.Exactly -> c.px
            is Constraint.AtMost -> minOf(c.px, natural)
            Constraint.Unbounded -> natural
        }
    }

    private fun row(vararg mods: (ContainerStyle) -> ContainerStyle) = mods.fold(ContainerStyle(direction = Direction.ROW)) { acc, m -> m(acc) }
    private fun column() = ContainerStyle(direction = Direction.COLUMN)
    private fun ex(px: Float) = Constraint.Exactly(px)
    private fun px(v: Float) = Length.Px(v)

    private fun assertRect(expected: Rect, actual: Rect?) {
        requireNotNull(actual)
        assertEquals("x", expected.x, actual.x, 0.01f)
        assertEquals("y", expected.y, actual.y, 0.01f)
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    // ---- flex: main axis ---------------------------------------------------

    @Test
    fun rowPlacesChildrenWithGapAndStretchesCross() {
        val result = LayoutEngine.layout(row({ it.copy(columnGap = 10f) }), ex(300f), ex(50f), listOf(Fixed(50f, 20f), Fixed(30f, 40f)))
        assertRect(Rect(0f, 0f, 50f, 50f), result.rects[0])
        assertRect(Rect(60f, 0f, 30f, 50f), result.rects[1])
        assertEquals(300f, result.contentWidth)
    }

    @Test
    fun justifyContentModes() {
        val kids = listOf(Fixed(50f, 10f), Fixed(50f, 10f))
        fun xs(mode: Distribute) = LayoutEngine.layout(row({ it.copy(justifyContent = mode) }), ex(200f), ex(10f), kids).rects.map { it!!.x }
        assertEquals(listOf(0f, 50f), xs(Distribute.START))
        assertEquals(listOf(100f, 150f), xs(Distribute.END))
        assertEquals(listOf(50f, 100f), xs(Distribute.CENTER))
        assertEquals(listOf(0f, 150f), xs(Distribute.SPACE_BETWEEN))
        assertEquals(listOf(25f, 125f), xs(Distribute.SPACE_AROUND))
        assertEquals(listOf(100f / 3, 200f / 3 + 50f).map { (it * 100).toInt() / 100f }, xs(Distribute.SPACE_EVENLY).map { (it * 100).toInt() / 100f })
    }

    @Test
    fun flexGrowSharesFreeSpaceByFactor() {
        val kids = listOf(
            Fixed(0f, 10f, BoxStyle(flexGrow = 1f, flexBasis = px(0f))),
            Fixed(0f, 10f, BoxStyle(flexGrow = 2f, flexBasis = px(0f))),
            Fixed(60f, 10f),
        )
        val r = LayoutEngine.layout(row(), ex(300f), ex(10f), kids).rects
        assertEquals(80f, r[0]!!.width, 0.01f)
        assertEquals(160f, r[1]!!.width, 0.01f)
        assertEquals(60f, r[2]!!.width, 0.01f)
        assertEquals(240f, r[2]!!.x, 0.01f)
    }

    @Test
    fun flexShrinkIsWeightedByBaseSizeAndRespectsMin() {
        val kids = listOf(
            Fixed(200f, 10f, BoxStyle(width = px(200f))),
            Fixed(100f, 10f, BoxStyle(width = px(100f))),
        )
        val r = LayoutEngine.layout(row(), ex(150f), ex(10f), kids).rects
        assertEquals(100f, r[0]!!.width, 0.01f) // overflow 150, shared 2:1
        assertEquals(50f, r[1]!!.width, 0.01f)

        val clamped = listOf(
            Fixed(200f, 10f, BoxStyle(width = px(200f), minWidth = px(180f))),
            Fixed(100f, 10f, BoxStyle(width = px(100f))),
        )
        val c = LayoutEngine.layout(row(), ex(150f), ex(10f), clamped).rects
        assertEquals(180f, c[0]!!.width, 0.01f)
        assertEquals(0f, c[1]!!.width, 0.01f) // takes the rest of the overflow
    }

    @Test
    fun maxWidthFreezesAndRedistributes() {
        val kids = listOf(
            Fixed(0f, 10f, BoxStyle(flexGrow = 1f, flexBasis = px(0f), maxWidth = px(50f))),
            Fixed(0f, 10f, BoxStyle(flexGrow = 1f, flexBasis = px(0f))),
        )
        val r = LayoutEngine.layout(row(), ex(300f), ex(10f), kids).rects
        assertEquals(50f, r[0]!!.width, 0.01f)
        assertEquals(250f, r[1]!!.width, 0.01f)
    }

    @Test
    fun autoMarginsAbsorbFreeSpace() {
        val kids = listOf(Fixed(50f, 10f), Fixed(50f, 10f, BoxStyle(marginLeft = Length.Auto)))
        val r = LayoutEngine.layout(row({ it.copy(justifyContent = Distribute.CENTER) }), ex(300f), ex(100f), kids).rects
        assertEquals(0f, r[0]!!.x, 0.01f)
        assertEquals(250f, r[1]!!.x, 0.01f)

        val centered = LayoutEngine.layout(row(), ex(300f), ex(100f), listOf(Fixed(50f, 10f, BoxStyle(marginLeft = Length.Auto, marginRight = Length.Auto, marginTop = Length.Auto, marginBottom = Length.Auto)))).rects[0]!!
        assertRect(Rect(125f, 45f, 50f, 10f), centered)
    }

    @Test
    fun reverseDirectionsMirror() {
        val r = LayoutEngine.layout(ContainerStyle(direction = Direction.ROW_REVERSE), ex(200f), ex(10f), listOf(Fixed(50f, 10f), Fixed(30f, 10f))).rects
        assertEquals(150f, r[0]!!.x, 0.01f)
        assertEquals(120f, r[1]!!.x, 0.01f)
    }

    @Test
    fun percentWidthsAndBasisResolveAgainstContainer() {
        val kids = listOf(Fixed(0f, 10f, BoxStyle(width = Length.Percent(25f))), Fixed(0f, 10f, BoxStyle(flexBasis = Length.Percent(50f))))
        val r = LayoutEngine.layout(row(), ex(400f), ex(10f), kids).rects
        assertEquals(100f, r[0]!!.width, 0.01f)
        assertEquals(200f, r[1]!!.width, 0.01f)
    }

    @Test
    fun orderAndDisplayNone() {
        val kids = listOf(Fixed(10f, 10f, BoxStyle(order = 2)), Fixed(20f, 10f, BoxStyle(displayNone = true)), Fixed(30f, 10f))
        val r = LayoutEngine.layout(row(), ex(100f), ex(10f), kids).rects
        assertEquals(30f, r[0]!!.x, 0.01f)
        assertNull(r[1])
        assertEquals(0f, r[2]!!.x, 0.01f)
    }

    // ---- flex: sizing and wrapping -------------------------------------------

    @Test
    fun contentSizedContainerUsesItsChildren() {
        val result = LayoutEngine.layout(row({ it.copy(columnGap = 5f) }), Constraint.AtMost(500f), Constraint.Unbounded, listOf(Fixed(40f, 20f), Fixed(60f, 30f)))
        assertEquals(105f, result.contentWidth, 0.01f)
        assertEquals(30f, result.contentHeight, 0.01f)
        assertEquals(30f, result.rects[0]!!.height, 0.01f) // stretched to the line
    }

    @Test
    fun columnStretchesWidthAndAlignsCenter() {
        val stretched = LayoutEngine.layout(column(), ex(200f), Constraint.Unbounded, listOf(Text(5), Fixed(40f, 10f)))
        assertRect(Rect(0f, 0f, 200f, 20f), stretched.rects[0])
        assertRect(Rect(0f, 20f, 200f, 10f), stretched.rects[1])
        assertEquals(30f, stretched.contentHeight, 0.01f)

        val centered = LayoutEngine.layout(column().copy(alignItems = Align.CENTER), ex(200f), Constraint.Unbounded, listOf(Fixed(40f, 10f)))
        assertRect(Rect(80f, 0f, 40f, 10f), centered.rects[0])
    }

    @Test
    fun textWrapsToItsFinalFlexWidth() {
        // A 300px-long label next to a 100px icon in a 250px row: the label shrinks to 150px and wraps to 2 lines.
        val kids = listOf(Fixed(100f, 40f, BoxStyle(width = px(100f), flexShrink = 0f)), Text(30))
        val result = LayoutEngine.layout(row().copy(alignItems = Align.START), ex(250f), Constraint.Unbounded, kids)
        assertEquals(150f, result.rects[1]!!.width, 0.01f)
        assertEquals(40f, result.rects[1]!!.height, 0.01f)
        assertEquals(40f, result.contentHeight, 0.01f)
    }

    @Test
    fun wrapBreaksLinesAndAlignContent() {
        val kids = List(5) { Fixed(40f, 10f) }
        val r = LayoutEngine.layout(row({ it.copy(wrap = true, columnGap = 10f, rowGap = 5f) }), ex(140f), Constraint.Unbounded, kids)
        assertEquals(listOf(0f, 50f, 100f, 0f, 50f), r.rects.map { it!!.x })
        assertEquals(listOf(0f, 0f, 0f, 15f, 15f), r.rects.map { it!!.y })
        assertEquals(25f, r.contentHeight, 0.01f)

        val stretched = LayoutEngine.layout(row({ it.copy(wrap = true) }), ex(80f), ex(100f), List(4) { Fixed(40f, 10f) })
        assertEquals(listOf(0f, 0f, 50f, 50f), stretched.rects.map { it!!.y })
        assertEquals(50f, stretched.rects[0]!!.height, 0.01f)
    }

    @Test
    fun aspectRatioDerivesTheOtherSide() {
        val r = LayoutEngine.layout(row().copy(alignItems = Align.START), ex(300f), ex(300f), listOf(Fixed(0f, 0f, BoxStyle(width = px(160f), aspectRatio = 16f / 9f)))).rects[0]!!
        assertEquals(90f, r.height, 0.01f)
    }

    // ---- grid ------------------------------------------------------------------

    private fun grid(columns: String, colGap: Float = 0f, rowGap: Float = 0f) =
        ContainerStyle(display = Display.GRID, columns = GridTracks.parse(columns)!!, columnGap = colGap, rowGap = rowGap)

    @Test
    fun gridFractionColumnsWithGapsAndAutoRows() {
        val kids = listOf(Fixed(10f, 30f), Fixed(10f, 50f), Fixed(10f, 20f), Fixed(10f, 10f))
        val r = LayoutEngine.layout(grid("repeat(3, 1fr)", colGap = 10f, rowGap = 5f), ex(320f), Constraint.Unbounded, kids)
        assertRect(Rect(0f, 0f, 100f, 50f), r.rects[0])   // stretched to the row's height
        assertRect(Rect(110f, 0f, 100f, 50f), r.rects[1])
        assertRect(Rect(220f, 0f, 100f, 50f), r.rects[2])
        assertRect(Rect(0f, 55f, 100f, 10f), r.rects[3])
        assertEquals(65f, r.contentHeight, 0.01f)
    }

    @Test
    fun gridMixedTracksSpanAndLinePlacement() {
        val kids = listOf(
            Fixed(70f, 10f, BoxStyle(gridColumn = GridTracks.Placement(null, 2))),
            Fixed(10f, 10f),
            Fixed(10f, 10f, BoxStyle(gridColumn = GridTracks.Placement(3, 1))),
        )
        // 50px | 1fr | auto (content 10px)
        val r = LayoutEngine.layout(grid("50px 1fr auto"), ex(200f), Constraint.Unbounded, kids).rects
        assertRect(Rect(0f, 0f, 190f, 10f), r[0])
        assertRect(Rect(190f, 0f, 10f, 10f), r[1])
        assertRect(Rect(190f, 10f, 10f, 10f), r[2])
    }

    @Test
    fun gridItemAlignmentInCell() {
        val c = grid("100px 100px").copy(justifyItems = Align.CENTER, alignItems = Align.END)
        val r = LayoutEngine.layout(c, ex(200f), Constraint.Unbounded, listOf(Fixed(40f, 10f), Fixed(40f, 30f))).rects
        assertRect(Rect(30f, 20f, 40f, 10f), r[0])
        assertRect(Rect(130f, 0f, 40f, 30f), r[1])
    }

    @Test
    fun gridWithoutTemplateIsOneStretchedColumn() {
        val r = LayoutEngine.layout(ContainerStyle(display = Display.GRID, rowGap = 4f), ex(120f), Constraint.Unbounded, listOf(Fixed(10f, 10f), Fixed(10f, 10f))).rects
        assertRect(Rect(0f, 0f, 120f, 10f), r[0])
        assertRect(Rect(0f, 14f, 120f, 10f), r[1])
    }

    // ---- stack and absolute ----------------------------------------------------

    @Test
    fun stackLayersAtTopLeftAndSizesToContent() {
        val result = LayoutEngine.layout(ContainerStyle(display = Display.STACK, alignItems = Align.START, justifyItems = Align.START), Constraint.AtMost(300f), Constraint.Unbounded, listOf(Fixed(80f, 20f), Fixed(40f, 50f)))
        assertRect(Rect(0f, 0f, 80f, 20f), result.rects[0])
        assertRect(Rect(0f, 0f, 40f, 50f), result.rects[1])
        assertEquals(80f, result.contentWidth, 0.01f)
        assertEquals(50f, result.contentHeight, 0.01f)
    }

    @Test
    fun stackCentersWhenAsked() {
        val c = ContainerStyle(display = Display.STACK, alignItems = Align.CENTER, justifyItems = Align.CENTER)
        val r = LayoutEngine.layout(c, ex(100f), ex(100f), listOf(Fixed(20f, 10f))).rects[0]
        assertRect(Rect(40f, 45f, 20f, 10f), r)
    }

    @Test
    fun absoluteChildrenUseThePaddingBox() {
        val c = ContainerStyle(display = Display.FLEX, paddingLeft = 10f, paddingTop = 10f, paddingRight = 10f, paddingBottom = 10f)
        val kids = listOf(
            Fixed(0f, 0f, BoxStyle(position = Position.ABSOLUTE, left = px(0f), right = px(0f), top = px(0f), bottom = px(0f))),
            Fixed(20f, 20f, BoxStyle(position = Position.ABSOLUTE, right = px(5f), bottom = px(5f))),
            Fixed(20f, 20f, BoxStyle(position = Position.ABSOLUTE, left = Length.Percent(50f), top = px(0f))),
        )
        val r = LayoutEngine.layout(c, ex(100f), ex(50f), kids).rects
        assertRect(Rect(-10f, -10f, 120f, 70f), r[0])  // covers the whole padding box
        assertRect(Rect(85f, 35f, 20f, 20f), r[1])
        assertRect(Rect(50f, -10f, 20f, 20f), r[2])
    }

    @Test
    fun absoluteChildDoesNotTakeSpaceInFlow() {
        val kids = listOf(Fixed(50f, 10f, BoxStyle(position = Position.ABSOLUTE)), Fixed(30f, 10f))
        val r = LayoutEngine.layout(row(), ex(100f), ex(10f), kids)
        assertEquals(0f, r.rects[1]!!.x, 0.01f)
    }

    // ---- from CSS -------------------------------------------------------------

    @Test
    fun containerAndBoxStyleFromComputedStyle() {
        val resolver = StyleResolver(emptyList(), listOf(CssParser.parse(
            ".g { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px 8px; justify-content: center } " +
                ".i { width: 50%; margin: 0 auto; flex: 2 1 10px; align-self: center; grid-column: span 2; order: -1; box-sizing: content-box; padding: 5px; border: 1px solid }",
        )))
        val g = ContainerStyle.from(resolver.resolve(StyleNode("box", staticClasses = setOf("g")), null))
        assertEquals(Display.GRID, g.display)
        assertEquals(2, g.columns.size)
        assertEquals(4f, g.rowGap)
        assertEquals(8f, g.columnGap)
        assertEquals(Distribute.CENTER, g.justifyContent)
        val i = BoxStyle.from(resolver.resolve(StyleNode("box", staticClasses = setOf("i")), null))
        assertEquals(Length.Percent(50f), i.width)
        assertEquals(Length.Auto, i.marginLeft)
        assertEquals(2f, i.flexGrow)
        assertEquals(px(10f), i.flexBasis)
        assertEquals(Align.CENTER, i.alignSelf)
        assertEquals(2, i.gridColumn.span)
        assertEquals(-1, i.order)
        assertEquals(12f, i.extraWidth) // content-box: 5+5 padding + 1+1 border
    }
}
