package com.khumam.gbaduo.companion.layout

import com.khumam.gbaduo.companion.style.GridTracks
import com.khumam.gbaduo.companion.style.Length

/**
 * CSS-like box layout: flexbox, a practical subset of grid, a layered "stack",
 * and absolute positioning. Works on abstract [LayoutChild]ren so it's pure and
 * unit-testable; `BoxLayout` (the Android view) feeds it real views.
 *
 * Deliberate simplifications (documented for pack authors): `min-width/height:
 * auto` is 0 (not min-content), baseline alignment acts as start, and grid
 * placement is row-major auto-placement with optional line numbers and spans.
 */
object LayoutEngine {

    private const val INF = Float.POSITIVE_INFINITY

    fun layout(container: ContainerStyle, width: Constraint, height: Constraint, children: List<LayoutChild>): LayoutResult {
        val rects = arrayOfNulls<Rect>(children.size)
        val inFlow = children.indices
            .filter { !children[it].style.displayNone && children[it].style.position != Position.ABSOLUTE }
            .sortedBy { children[it].style.order } // stable: equal `order` keeps source order
        val (contentWidth, contentHeight) = when (container.display) {
            Display.FLEX -> flex(container, width, height, children, inFlow, rects)
            Display.GRID -> grid(container, width, height, children, inFlow, rects)
            Display.STACK -> stack(container, width, height, children, inFlow, rects)
        }
        for (i in children.indices) {
            val child = children[i]
            if (!child.style.displayNone && child.style.position == Position.ABSOLUTE) {
                rects[i] = absolute(container, contentWidth, contentHeight, child)
            }
        }
        return LayoutResult(contentWidth, contentHeight, rects.toList())
    }

    // ---- shared helpers -------------------------------------------------------

    private fun resolve(length: Length, base: Float?): Float? = length.resolve(base)

    private fun clamp(value: Float, min: Float, max: Float): Float = maxOf(min, minOf(value, max))

    private fun margin(length: Length, containerWidth: Float?): Float = when (length) {
        is Length.Px -> length.px
        is Length.Percent -> containerWidth?.let { it * length.percent / 100f } ?: 0f
        Length.Auto -> 0f
    }

    private class Sizing(
        val width: Float?, val height: Float?,
        val minWidth: Float, val maxWidth: Float,
        val minHeight: Float, val maxHeight: Float,
    )

    /** Definite sizes (incl. aspect-ratio) and min/max limits of [style] in a container of [containerWidth] x [containerHeight]. */
    private fun sizing(style: BoxStyle, containerWidth: Float?, containerHeight: Float?): Sizing {
        var w = resolve(style.width, containerWidth)?.plus(style.extraWidth)
        var h = resolve(style.height, containerHeight)?.plus(style.extraHeight)
        val ratio = style.aspectRatio
        if (ratio != null) {
            if (w == null && h != null) w = h * ratio
            if (h == null && w != null) h = w / ratio
        }
        val minW = resolve(style.minWidth, containerWidth)?.plus(style.extraWidth) ?: 0f
        val maxW = style.maxWidth?.let { resolve(it, containerWidth)?.plus(style.extraWidth) } ?: INF
        val minH = resolve(style.minHeight, containerHeight)?.plus(style.extraHeight) ?: 0f
        val maxH = style.maxHeight?.let { resolve(it, containerHeight)?.plus(style.extraHeight) } ?: INF
        return Sizing(w?.let { clamp(it, minW, maxW) }, h?.let { clamp(it, minH, maxH) }, minW, maxW, minH, maxH)
    }

    private fun exactly(px: Float) = Constraint.Exactly(maxOf(0f, px))

    /** Start offset and extra spacing for distributing [free] space among [count] items. */
    private fun distribute(mode: Distribute, free: Float, count: Int): Pair<Float, Float> = when (mode) {
        Distribute.START, Distribute.STRETCH -> 0f to 0f
        Distribute.END -> free to 0f
        Distribute.CENTER -> free / 2f to 0f
        Distribute.SPACE_BETWEEN -> if (count > 1 && free > 0) 0f to free / (count - 1) else 0f to 0f
        Distribute.SPACE_AROUND -> if (free > 0 && count > 0) free / count / 2f to free / count else free / 2f to 0f
        Distribute.SPACE_EVENLY -> if (free > 0 && count > 0) free / (count + 1) to free / (count + 1) else free / 2f to 0f
    }

    private fun alignOffset(align: Align, free: Float): Float = when (align) {
        Align.END -> free
        Align.CENTER -> free / 2f
        else -> 0f
    }

    // ---- flexbox --------------------------------------------------------------

    private class FlexItem(val index: Int, val child: LayoutChild) {
        var marginMainStart = 0f
        var marginMainEnd = 0f
        var marginCrossStart = 0f
        var marginCrossEnd = 0f
        var autoMainStart = false
        var autoMainEnd = false
        var autoCrossStart = false
        var autoCrossEnd = false
        var mainDefinite: Float? = null
        var crossDefinite: Float? = null
        var minMain = 0f
        var maxMain = INF
        var minCross = 0f
        var maxCross = INF
        var base = 0f
        var hypothetical = 0f
        var target = 0f
        var frozen = false
        var cross = 0f
        var mainPos = 0f
        var crossPos = 0f
        val mainMargins get() = marginMainStart + marginMainEnd
        val crossMargins get() = marginCrossStart + marginCrossEnd
    }

    private fun flex(
        container: ContainerStyle,
        width: Constraint,
        height: Constraint,
        children: List<LayoutChild>,
        inFlow: List<Int>,
        rects: Array<Rect?>,
    ): Pair<Float, Float> {
        val isRow = container.direction == Direction.ROW || container.direction == Direction.ROW_REVERSE
        val mainC = if (isRow) width else height
        val crossC = if (isRow) height else width
        val mainGap = if (isRow) container.columnGap else container.rowGap
        val crossGap = if (isRow) container.rowGap else container.columnGap
        val containerMainDef = mainC.definite
        val containerCrossDef = crossC.definite
        val containerWidthDef = width.definite

        fun alignOf(item: FlexItem) = item.child.style.alignSelf ?: container.alignItems

        fun main(size: Size) = if (isRow) size.width else size.height
        fun crossOf(size: Size) = if (isRow) size.height else size.width
        fun measure(item: FlexItem, mainConstraint: Constraint, crossConstraint: Constraint): Size =
            if (isRow) item.child.measure(mainConstraint, crossConstraint) else item.child.measure(crossConstraint, mainConstraint)

        fun crossConstraintFor(item: FlexItem): Constraint {
            item.crossDefinite?.let { return exactly(it) }
            if (alignOf(item) == Align.STRETCH && containerCrossDef != null && !item.autoCrossStart && !item.autoCrossEnd && !container.wrap) {
                return exactly(clamp(containerCrossDef - item.crossMargins, item.minCross, item.maxCross))
            }
            return crossC.limit?.let { Constraint.AtMost(maxOf(0f, minOf(it - item.crossMargins, item.maxCross))) } ?: Constraint.Unbounded
        }

        val items = inFlow.map { index ->
            val style = children[index].style
            FlexItem(index, children[index]).apply {
                val top = margin(style.marginTop, containerWidthDef)
                val right = margin(style.marginRight, containerWidthDef)
                val bottom = margin(style.marginBottom, containerWidthDef)
                val left = margin(style.marginLeft, containerWidthDef)
                if (isRow) {
                    marginMainStart = left; marginMainEnd = right; marginCrossStart = top; marginCrossEnd = bottom
                    autoMainStart = style.marginLeft == Length.Auto; autoMainEnd = style.marginRight == Length.Auto
                    autoCrossStart = style.marginTop == Length.Auto; autoCrossEnd = style.marginBottom == Length.Auto
                } else {
                    marginMainStart = top; marginMainEnd = bottom; marginCrossStart = left; marginCrossEnd = right
                    autoMainStart = style.marginTop == Length.Auto; autoMainEnd = style.marginBottom == Length.Auto
                    autoCrossStart = style.marginLeft == Length.Auto; autoCrossEnd = style.marginRight == Length.Auto
                }
                val sz = sizing(style, width.definite, height.definite)
                mainDefinite = if (isRow) sz.width else sz.height
                crossDefinite = if (isRow) sz.height else sz.width
                minMain = if (isRow) sz.minWidth else sz.minHeight
                maxMain = if (isRow) sz.maxWidth else sz.maxHeight
                minCross = if (isRow) sz.minHeight else sz.minWidth
                maxCross = if (isRow) sz.maxHeight else sz.maxWidth

                val extraMain = if (isRow) style.extraWidth else style.extraHeight
                val basis = when (val b = style.flexBasis) {
                    is Length.Px -> b.px + extraMain
                    is Length.Percent -> containerMainDef?.let { it * b.percent / 100f + extraMain }
                    Length.Auto -> null
                }
                base = basis ?: mainDefinite ?: main(measure(this, Constraint.Unbounded, crossConstraintFor(this)))
                hypothetical = clamp(base, minMain, maxMain)
            }
        }

        // Lines.
        val breakLimit = mainC.limit ?: INF
        val lines = mutableListOf<MutableList<FlexItem>>()
        var lineSum = 0f
        for (item in items) {
            val outer = item.hypothetical + item.mainMargins
            val current = lines.lastOrNull()
            if (current == null || (container.wrap && current.isNotEmpty() && lineSum + mainGap + outer > breakLimit + 0.01f)) {
                lines += mutableListOf(item)
                lineSum = outer
            } else {
                current += item
                lineSum += mainGap + outer
            }
        }
        fun hypotheticalLineMain(line: List<FlexItem>) = line.sumOf { (it.hypothetical + it.mainMargins).toDouble() }.toFloat() + mainGap * (line.size - 1).coerceAtLeast(0)
        val containerMain = containerMainDef ?: run {
            val longest = lines.maxOfOrNull { hypotheticalLineMain(it) } ?: 0f
            mainC.limit?.let { minOf(it, longest) } ?: longest
        }

        // Resolve flexible lengths (CSS Flexbox §9.7), per line.
        for (line in lines) {
            val available = containerMain - mainGap * (line.size - 1) - line.sumOf { it.mainMargins.toDouble() }.toFloat()
            resolveFlexibleLengths(line, available)
        }

        // Cross sizes.
        for (item in items) {
            item.cross = item.crossDefinite
                ?: clamp(crossOf(measure(item, exactly(item.target), crossConstraintFor(item))), item.minCross, item.maxCross)
        }
        val lineCrosses = lines.map { line -> line.maxOfOrNull { it.cross + it.crossMargins } ?: 0f }.toMutableList()
        if (!container.wrap && containerCrossDef != null && lineCrosses.size == 1) lineCrosses[0] = containerCrossDef
        val linesCrossSum = lineCrosses.sum() + crossGap * (lineCrosses.size - 1).coerceAtLeast(0)
        val containerCross = containerCrossDef ?: crossC.limit?.let { minOf(it, linesCrossSum) } ?: linesCrossSum

        // align-content (multi-line).
        var lineStart = 0f
        var lineSpacing = 0f
        if (container.wrap && lines.isNotEmpty()) {
            val free = containerCross - linesCrossSum
            if (container.alignContent == Distribute.STRETCH) {
                if (free > 0) for (i in lineCrosses.indices) lineCrosses[i] += free / lineCrosses.size
            } else {
                val (start, spacing) = distribute(container.alignContent, free, lines.size)
                lineStart = start
                lineSpacing = spacing
            }
        }

        // Positions.
        var crossCursor = lineStart
        for ((lineIndex, line) in lines.withIndex()) {
            val lineCross = lineCrosses[lineIndex]
            // Main axis.
            val used = line.sumOf { (it.target + it.mainMargins).toDouble() }.toFloat() + mainGap * (line.size - 1)
            val free = containerMain - used
            val autoCount = line.count { it.autoMainStart } + line.count { it.autoMainEnd }
            val (start, spacing) = if (autoCount > 0) 0f to 0f else distribute(container.justifyContent, free, line.size)
            val autoShare = if (autoCount > 0 && free > 0) free / autoCount else 0f
            var cursor = start
            for (item in line) {
                cursor += item.marginMainStart + (if (item.autoMainStart) autoShare else 0f)
                item.mainPos = cursor
                cursor += item.target + item.marginMainEnd + (if (item.autoMainEnd) autoShare else 0f) + mainGap + spacing
            }
            // Cross axis.
            for (item in line) {
                val align = alignOf(item)
                if (align == Align.STRETCH && item.crossDefinite == null && !item.autoCrossStart && !item.autoCrossEnd) {
                    item.cross = clamp(lineCross - item.crossMargins, item.minCross, item.maxCross)
                }
                val crossFree = lineCross - item.cross - item.crossMargins
                val offset = when {
                    item.autoCrossStart && item.autoCrossEnd -> crossFree / 2f
                    item.autoCrossStart -> crossFree
                    item.autoCrossEnd -> 0f
                    else -> alignOffset(align, crossFree)
                }
                item.crossPos = crossCursor + item.marginCrossStart + offset
            }
            crossCursor += lineCross + crossGap + lineSpacing
        }

        val reverseMain = container.direction == Direction.ROW_REVERSE || container.direction == Direction.COLUMN_REVERSE
        for (item in items) {
            val mainPos = if (reverseMain) containerMain - item.mainPos - item.target else item.mainPos
            val crossPos = if (container.wrapReverse) containerCross - item.crossPos - item.cross else item.crossPos
            rects[item.index] = if (isRow) Rect(mainPos, crossPos, item.target, item.cross) else Rect(crossPos, mainPos, item.cross, item.target)
        }
        return if (isRow) containerMain to containerCross else containerCross to containerMain
    }

    private fun resolveFlexibleLengths(line: List<FlexItem>, available: Float) {
        val growing = line.sumOf { it.hypothetical.toDouble() } < available
        for (item in line) {
            val style = item.child.style
            val factor = if (growing) style.flexGrow else style.flexShrink
            item.frozen = factor == 0f || (growing && item.base > item.hypothetical) || (!growing && item.base < item.hypothetical)
            item.target = if (item.frozen) item.hypothetical else item.base
        }
        val initialFree = available - line.sumOf { (if (it.frozen) it.target else it.base).toDouble() }.toFloat()
        repeat(line.size + 1) {
            val unfrozen = line.filter { !it.frozen }
            if (unfrozen.isEmpty()) return
            var remaining = available - line.filter { it.frozen }.sumOf { it.target.toDouble() }.toFloat() - unfrozen.sumOf { it.base.toDouble() }.toFloat()
            val sumFactors = unfrozen.sumOf { (if (growing) it.child.style.flexGrow else it.child.style.flexShrink).toDouble() }.toFloat()
            if (sumFactors < 1f) {
                val scaled = initialFree * sumFactors
                if (kotlin.math.abs(scaled) < kotlin.math.abs(remaining)) remaining = scaled
            }
            if (growing) {
                for (item in unfrozen) item.target = item.base + if (sumFactors > 0f) remaining * item.child.style.flexGrow / sumFactors else 0f
            } else {
                val sumScaled = unfrozen.sumOf { (it.child.style.flexShrink * it.base).toDouble() }.toFloat()
                for (item in unfrozen) {
                    item.target = item.base + if (sumScaled > 0f) remaining * (item.child.style.flexShrink * item.base) / sumScaled else 0f
                }
            }
            var totalViolation = 0f
            val violations = unfrozen.associateWith { item ->
                val clamped = clamp(item.target, item.minMain, item.maxMain).coerceAtLeast(0f)
                val v = clamped - item.target
                item.target = clamped
                totalViolation += v
                v
            }
            when {
                kotlin.math.abs(totalViolation) < 0.001f -> unfrozen.forEach { it.frozen = true }
                totalViolation > 0 -> violations.filter { it.value > 0 }.keys.forEach { it.frozen = true }
                else -> violations.filter { it.value < 0 }.keys.forEach { it.frozen = true }
            }
        }
    }

    // ---- grid -----------------------------------------------------------------

    private class GridItem(val index: Int, val child: LayoutChild, val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int)

    private fun grid(
        container: ContainerStyle,
        width: Constraint,
        height: Constraint,
        children: List<LayoutChild>,
        inFlow: List<Int>,
        rects: Array<Rect?>,
    ): Pair<Float, Float> {
        val columnTracks = container.columns.ifEmpty { listOf(GridTracks.Track.Auto) }
        val nCols = columnTracks.size
        val containerW = width.definite
        val containerH = height.definite

        // Placement (row-major auto-placement, honoring line numbers and spans).
        val occupied = mutableListOf<BooleanArray>()
        fun free(r: Int, c: Int, rs: Int, cs: Int): Boolean {
            for (rr in r until r + rs) {
                val row = occupied.getOrNull(rr) ?: continue
                for (cc in c until c + cs) if (row[cc]) return false
            }
            return true
        }
        fun occupy(r: Int, c: Int, rs: Int, cs: Int) {
            while (occupied.size < r + rs) occupied += BooleanArray(nCols)
            for (rr in r until r + rs) for (cc in c until c + cs) occupied[rr][cc] = true
        }
        var cursorRow = 0
        var cursorCol = 0
        val items = inFlow.map { index ->
            val style = children[index].style
            val cs = style.gridColumn.span.coerceIn(1, nCols)
            val rs = style.gridRow.span.coerceAtLeast(1)
            val fixedCol = style.gridColumn.start?.let { (it - 1).coerceIn(0, nCols - cs) }
            val fixedRow = style.gridRow.start?.let { it - 1 }
            var row: Int
            var col: Int
            when {
                fixedRow != null && fixedCol != null -> {
                    row = fixedRow
                    col = fixedCol
                }
                fixedRow != null -> {
                    row = fixedRow
                    col = (0..nCols - cs).firstOrNull { free(row, it, rs, cs) } ?: 0
                }
                fixedCol != null -> {
                    col = fixedCol
                    row = cursorRow
                    while (!free(row, col, rs, cs)) row++
                }
                else -> {
                    row = cursorRow
                    col = cursorCol
                    while (true) {
                        if (col + cs > nCols) {
                            row++
                            col = 0
                            continue
                        }
                        if (free(row, col, rs, cs)) break
                        col++
                    }
                    cursorRow = row
                    cursorCol = col + cs
                }
            }
            occupy(row, col, rs, cs)
            GridItem(index, children[index], row, col, rs, cs)
        }
        val nRows = maxOf(occupied.size, container.rows.size)

        fun marginsH(item: GridItem) = margin(item.child.style.marginLeft, containerW) + margin(item.child.style.marginRight, containerW)
        fun marginsV(item: GridItem) = margin(item.child.style.marginTop, containerW) + margin(item.child.style.marginBottom, containerW)

        // Column sizes.
        val colGaps = container.columnGap * (nCols - 1)
        val colSizes = FloatArray(nCols)
        fun maxContentWidth(col: Int): Float = items.filter { it.col == col && it.colSpan == 1 }.maxOfOrNull { item ->
            val sz = sizing(item.child.style, null, containerH)
            (sz.width ?: clamp(item.child.measure(Constraint.Unbounded, sz.height?.let { exactly(it) } ?: Constraint.Unbounded).width, sz.minWidth, sz.maxWidth)) + marginsH(item)
        } ?: 0f

        fun sizeColumns(available: Float?) {
            var frSum = 0f
            for ((i, track) in columnTracks.withIndex()) {
                colSizes[i] = when (track) {
                    is GridTracks.Track.Fixed -> track.length.resolve(available) ?: maxContentWidth(i)
                    is GridTracks.Track.Fr -> {
                        frSum += track.fr
                        0f
                    }
                    GridTracks.Track.Auto -> maxContentWidth(i)
                }
            }
            if (frSum > 0f) {
                val frTracks = columnTracks.withIndex().filter { it.value is GridTracks.Track.Fr }
                if (available != null) {
                    val leftover = maxOf(0f, available - colGaps - colSizes.sum())
                    val unit = leftover / maxOf(frSum, 1f)
                    for ((i, t) in frTracks) colSizes[i] = unit * (t as GridTracks.Track.Fr).fr
                } else {
                    val unit = frTracks.maxOfOrNull { (i, t) -> val fr = (t as GridTracks.Track.Fr).fr; if (fr > 0f) maxContentWidth(i) / fr else 0f } ?: 0f
                    for ((i, t) in frTracks) colSizes[i] = unit * (t as GridTracks.Track.Fr).fr
                }
            } else if (available != null && container.justifyContent == Distribute.START) {
                // `normal` track alignment: auto tracks stretch into leftover space.
                val autoTracks = columnTracks.indices.filter { columnTracks[it] == GridTracks.Track.Auto }
                val leftover = available - colGaps - colSizes.sum()
                if (leftover > 0 && autoTracks.isNotEmpty()) for (i in autoTracks) colSizes[i] += leftover / autoTracks.size
            }
        }
        sizeColumns(containerW)
        if (containerW == null) {
            val limit = width.limit
            if (limit != null && colSizes.sum() + colGaps > limit) sizeColumns(limit)
        }
        val gridWidth = colSizes.sum() + colGaps

        fun cellWidth(item: GridItem): Float = (item.col until item.col + item.colSpan).sumOf { colSizes[it].toDouble() }.toFloat() + container.columnGap * (item.colSpan - 1)

        fun itemWidth(item: GridItem): Float {
            val cellW = cellWidth(item)
            val sz = sizing(item.child.style, cellW, null)
            sz.width?.let { return it }
            val justify = item.child.style.justifySelf ?: container.justifyItems
            return if (justify == Align.STRETCH) {
                clamp(cellW - marginsH(item), sz.minWidth, sz.maxWidth)
            } else {
                clamp(item.child.measure(Constraint.AtMost(maxOf(0f, cellW - marginsH(item))), Constraint.Unbounded).width, sz.minWidth, sz.maxWidth)
            }
        }

        // Row sizes.
        val rowGaps = container.rowGap * (nRows - 1).coerceAtLeast(0)
        val rowSizes = FloatArray(nRows)
        val itemWidths = items.associateWith { itemWidth(it) }
        fun contentHeight(item: GridItem): Float {
            val sz = sizing(item.child.style, cellWidth(item), null)
            return (sz.height ?: clamp(item.child.measure(exactly(itemWidths.getValue(item)), Constraint.Unbounded).height, sz.minHeight, sz.maxHeight)) + marginsV(item)
        }
        var rowFrSum = 0f
        for (r in 0 until nRows) {
            val track = container.rows.getOrNull(r) ?: GridTracks.Track.Auto
            rowSizes[r] = when {
                track is GridTracks.Track.Fixed && track.length.resolve(containerH) != null -> track.length.resolve(containerH)!!
                track is GridTracks.Track.Fr && containerH != null -> {
                    rowFrSum += track.fr
                    0f
                }
                else -> items.filter { it.row == r && it.rowSpan == 1 }.maxOfOrNull { contentHeight(it) } ?: 0f
            }
        }
        if (rowFrSum > 0f && containerH != null) {
            val leftover = maxOf(0f, containerH - rowGaps - rowSizes.sum())
            for (r in 0 until nRows) (container.rows.getOrNull(r) as? GridTracks.Track.Fr)?.let { rowSizes[r] = leftover / maxOf(rowFrSum, 1f) * it.fr }
        }
        // Items spanning rows: grow their last row if they don't fit.
        for (item in items.filter { it.rowSpan > 1 }) {
            val last = minOf(item.row + item.rowSpan, nRows) - 1
            val spanned = (item.row..last).sumOf { rowSizes[it].toDouble() }.toFloat() + container.rowGap * (last - item.row)
            val needed = contentHeight(item)
            if (needed > spanned) rowSizes[last] += needed - spanned
        }
        val gridHeight = rowSizes.sum() + rowGaps

        // Track alignment inside a larger container.
        val (colStart, colExtra) = if (containerW != null) distribute(container.justifyContent, containerW - gridWidth, nCols) else 0f to 0f
        val (rowStart, rowExtra) = if (containerH != null) distribute(container.alignContent, containerH - gridHeight, nRows) else 0f to 0f
        val colX = FloatArray(nCols)
        var x = colStart
        for (i in 0 until nCols) {
            colX[i] = x
            x += colSizes[i] + container.columnGap + colExtra
        }
        val rowY = FloatArray(nRows)
        var y = rowStart
        for (i in 0 until nRows) {
            rowY[i] = y
            y += rowSizes[i] + container.rowGap + rowExtra
        }

        for (item in items) {
            val style = item.child.style
            val cellX = colX[item.col]
            val cellY = rowY[item.row]
            val lastCol = item.col + item.colSpan - 1
            val lastRow = minOf(item.row + item.rowSpan, nRows) - 1
            val cellW = colX[lastCol] + colSizes[lastCol] - cellX
            val cellH = rowY[lastRow] + rowSizes[lastRow] - cellY
            val w = itemWidths.getValue(item)
            val sz = sizing(style, cellW, cellH)
            val align = style.alignSelf ?: container.alignItems
            val h = sz.height ?: if (align == Align.STRETCH) {
                clamp(cellH - marginsV(item), sz.minHeight, sz.maxHeight)
            } else {
                clamp(item.child.measure(exactly(w), Constraint.Unbounded).height, sz.minHeight, sz.maxHeight)
            }
            val justify = style.justifySelf ?: container.justifyItems
            val left = margin(style.marginLeft, containerW)
            val top = margin(style.marginTop, containerW)
            rects[item.index] = Rect(
                cellX + left + alignOffset(justify, cellW - w - marginsH(item)),
                cellY + top + alignOffset(align, cellH - h - marginsV(item)),
                w,
                h,
            )
        }
        val contentW = containerW ?: width.limit?.let { minOf(it, gridWidth) } ?: gridWidth
        val contentH = containerH ?: height.limit?.let { minOf(it, gridHeight) } ?: gridHeight
        return contentW to contentH
    }

    // ---- stack ------------------------------------------------------------------

    private fun stack(
        container: ContainerStyle,
        width: Constraint,
        height: Constraint,
        children: List<LayoutChild>,
        inFlow: List<Int>,
        rects: Array<Rect?>,
    ): Pair<Float, Float> {
        val containerW = width.definite
        val containerH = height.definite
        class Placed(val index: Int, val w: Float, val h: Float, val mL: Float, val mT: Float, val mR: Float, val mB: Float)
        val placed = inFlow.map { index ->
            val style = children[index].style
            val mL = margin(style.marginLeft, containerW)
            val mR = margin(style.marginRight, containerW)
            val mT = margin(style.marginTop, containerW)
            val mB = margin(style.marginBottom, containerW)
            val sz = sizing(style, containerW, containerH)
            val justify = style.justifySelf ?: container.justifyItems
            val align = style.alignSelf ?: container.alignItems
            val w = sz.width
                ?: if (justify == Align.STRETCH && containerW != null) clamp(containerW - mL - mR, sz.minWidth, sz.maxWidth) else null
            val h = sz.height
                ?: if (align == Align.STRETCH && containerH != null) clamp(containerH - mT - mB, sz.minHeight, sz.maxHeight) else null
            val measured = if (w == null || h == null) {
                children[index].measure(
                    w?.let { exactly(it) } ?: width.limit?.let { Constraint.AtMost(maxOf(0f, it - mL - mR)) } ?: Constraint.Unbounded,
                    h?.let { exactly(it) } ?: height.limit?.let { Constraint.AtMost(maxOf(0f, it - mT - mB)) } ?: Constraint.Unbounded,
                )
            } else {
                null
            }
            Placed(
                index,
                w ?: clamp(measured!!.width, sz.minWidth, sz.maxWidth),
                h ?: clamp(measured!!.height, sz.minHeight, sz.maxHeight),
                mL, mT, mR, mB,
            )
        }
        val contentW = containerW ?: (placed.maxOfOrNull { it.w + it.mL + it.mR } ?: 0f).let { c -> width.limit?.let { minOf(it, c) } ?: c }
        val contentH = containerH ?: (placed.maxOfOrNull { it.h + it.mT + it.mB } ?: 0f).let { c -> height.limit?.let { minOf(it, c) } ?: c }
        for (p in placed) {
            val style = children[p.index].style
            val justify = style.justifySelf ?: container.justifyItems
            val align = style.alignSelf ?: container.alignItems
            rects[p.index] = Rect(
                p.mL + alignOffset(justify, contentW - p.w - p.mL - p.mR),
                p.mT + alignOffset(align, contentH - p.h - p.mT - p.mB),
                p.w,
                p.h,
            )
        }
        return contentW to contentH
    }

    // ---- absolute positioning -------------------------------------------------

    private fun absolute(container: ContainerStyle, contentW: Float, contentH: Float, child: LayoutChild): Rect {
        val style = child.style
        val pbW = contentW + container.paddingLeft + container.paddingRight
        val pbH = contentH + container.paddingTop + container.paddingBottom
        val left = resolve(style.left, pbW)
        val right = resolve(style.right, pbW)
        val top = resolve(style.top, pbH)
        val bottom = resolve(style.bottom, pbH)
        val mL = margin(style.marginLeft, pbW)
        val mR = margin(style.marginRight, pbW)
        val mT = margin(style.marginTop, pbW)
        val mB = margin(style.marginBottom, pbW)
        val sz = sizing(style, pbW, pbH)
        val w = sz.width
            ?: if (left != null && right != null) clamp(pbW - left - right - mL - mR, sz.minWidth, sz.maxWidth) else null
        val h = sz.height
            ?: if (top != null && bottom != null) clamp(pbH - top - bottom - mT - mB, sz.minHeight, sz.maxHeight) else null
        val finalW = w ?: clamp(
            child.measure(Constraint.AtMost(maxOf(0f, pbW - (left ?: 0f) - (right ?: 0f) - mL - mR)), h?.let { exactly(it) } ?: Constraint.Unbounded).width,
            sz.minWidth, sz.maxWidth,
        )
        val finalH = h ?: clamp(
            child.measure(exactly(finalW), Constraint.AtMost(maxOf(0f, pbH - (top ?: 0f) - (bottom ?: 0f) - mT - mB))).height,
            sz.minHeight, sz.maxHeight,
        )
        val x = when {
            left != null -> -container.paddingLeft + left + mL
            right != null -> contentW + container.paddingRight - right - mR - finalW
            else -> mL
        }
        val y = when {
            top != null -> -container.paddingTop + top + mT
            bottom != null -> contentH + container.paddingBottom - bottom - mB - finalH
            else -> mT
        }
        return Rect(x, y, finalW, finalH)
    }
}
