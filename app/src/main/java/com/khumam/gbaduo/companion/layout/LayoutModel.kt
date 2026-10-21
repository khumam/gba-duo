package com.khumam.gbaduo.companion.layout

import com.khumam.gbaduo.companion.style.ComputedStyle
import com.khumam.gbaduo.companion.style.GridTracks
import com.khumam.gbaduo.companion.style.Length

/** Available space for measuring, like Android's MeasureSpec modes. */
sealed class Constraint {
    data class Exactly(val px: Float) : Constraint()
    data class AtMost(val px: Float) : Constraint()
    object Unbounded : Constraint() {
        override fun toString() = "Unbounded"
    }

    val definite: Float? get() = (this as? Exactly)?.px
    val limit: Float? get() = when (this) {
        is Exactly -> px
        is AtMost -> px
        Unbounded -> null
    }

    fun shrink(by: Float): Constraint = when (this) {
        is Exactly -> Exactly(maxOf(0f, px - by))
        is AtMost -> AtMost(maxOf(0f, px - by))
        Unbounded -> Unbounded
    }
}

data class Size(val width: Float, val height: Float)

data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)

enum class Display { FLEX, GRID, STACK }
enum class Direction { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
enum class Position { STATIC, RELATIVE, ABSOLUTE }

/** Main-axis distribution (`justify-content`, and `align-content` for lines/tracks). */
enum class Distribute { START, END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY, STRETCH }

/** Cross-axis / in-cell alignment. `null` in [BoxStyle] means `auto` (use the container's). */
enum class Align { START, END, CENTER, STRETCH, BASELINE }

/** Layout-relevant style of one child. Lengths stay symbolic so % can resolve against the container. */
data class BoxStyle(
    val width: Length = Length.Auto,
    val height: Length = Length.Auto,
    val minWidth: Length = Length.Auto,
    val minHeight: Length = Length.Auto,
    val maxWidth: Length? = null,
    val maxHeight: Length? = null,
    val marginTop: Length = Length.Px(0f),
    val marginRight: Length = Length.Px(0f),
    val marginBottom: Length = Length.Px(0f),
    val marginLeft: Length = Length.Px(0f),
    val flexGrow: Float = 0f,
    val flexShrink: Float = 1f,
    val flexBasis: Length = Length.Auto,
    val alignSelf: Align? = null,
    val justifySelf: Align? = null,
    val order: Int = 0,
    val position: Position = Position.STATIC,
    val top: Length = Length.Auto,
    val right: Length = Length.Auto,
    val bottom: Length = Length.Auto,
    val left: Length = Length.Auto,
    val gridColumn: GridTracks.Placement = GridTracks.Placement(null, 1),
    val gridRow: GridTracks.Placement = GridTracks.Placement(null, 1),
    val aspectRatio: Float? = null,
    /** With `box-sizing: content-box`, padding + border to add to a specified width / height. */
    val extraWidth: Float = 0f,
    val extraHeight: Float = 0f,
    val displayNone: Boolean = false,
) {
    /** Converts every px length to device pixels (CSS px are Android dp). Percentages are unchanged. */
    fun scaled(factor: Float): BoxStyle {
        fun Length.s(): Length = when (this) {
            is Length.Px -> Length.Px(px * factor)
            is Length.Calc -> Length.Calc(px * factor, percent)
            else -> this
        }
        return copy(
            width = width.s(), height = height.s(), minWidth = minWidth.s(), minHeight = minHeight.s(),
            maxWidth = maxWidth?.s(), maxHeight = maxHeight?.s(),
            marginTop = marginTop.s(), marginRight = marginRight.s(), marginBottom = marginBottom.s(), marginLeft = marginLeft.s(),
            flexBasis = flexBasis.s(), top = top.s(), right = right.s(), bottom = bottom.s(), left = left.s(),
            extraWidth = extraWidth * factor, extraHeight = extraHeight * factor,
        )
    }

    companion object {
        val DEFAULT = BoxStyle()

        fun from(style: ComputedStyle): BoxStyle {
            val contentBox = style.keyword("box-sizing") == "content-box"
            val horizontalExtra = style.padding("left") + style.padding("right") + style.borderWidth("left") + style.borderWidth("right")
            val verticalExtra = style.padding("top") + style.padding("bottom") + style.borderWidth("top") + style.borderWidth("bottom")
            return BoxStyle(
                width = style.length("width"),
                height = style.length("height"),
                minWidth = style.length("min-width"),
                minHeight = style.length("min-height"),
                maxWidth = style.maxLength("max-width"),
                maxHeight = style.maxLength("max-height"),
                marginTop = style.length("margin-top"),
                marginRight = style.length("margin-right"),
                marginBottom = style.length("margin-bottom"),
                marginLeft = style.length("margin-left"),
                flexGrow = style.number("flex-grow", 0f),
                flexShrink = style.number("flex-shrink", 1f),
                flexBasis = if (style.keyword("flex-basis") == "content") Length.Auto else style.length("flex-basis"),
                alignSelf = align(style.keyword("align-self")),
                justifySelf = align(style.keyword("justify-self")),
                order = style.raw("order").trim().toIntOrNull() ?: 0,
                position = when (style.keyword("position")) {
                    "absolute" -> Position.ABSOLUTE
                    "relative" -> Position.RELATIVE
                    else -> Position.STATIC
                },
                top = style.length("top"),
                right = style.length("right"),
                bottom = style.length("bottom"),
                left = style.length("left"),
                gridColumn = GridTracks.placement(style.raw("grid-column")) ?: GridTracks.Placement(null, 1),
                gridRow = GridTracks.placement(style.raw("grid-row")) ?: GridTracks.Placement(null, 1),
                aspectRatio = style.aspectRatio,
                extraWidth = if (contentBox) horizontalExtra else 0f,
                extraHeight = if (contentBox) verticalExtra else 0f,
                displayNone = style.keyword("display") == "none",
            )
        }

        /** `auto` → null; `normal` → [Align.STRETCH] (the CSS default for flex and grid items). */
        fun align(keyword: String): Align? = when (keyword) {
            "auto" -> null
            "flex-start", "start", "left" -> Align.START
            "flex-end", "end", "right" -> Align.END
            "center" -> Align.CENTER
            "baseline" -> Align.BASELINE
            else -> Align.STRETCH
        }
    }
}

/** Layout-relevant style of a container. */
data class ContainerStyle(
    val display: Display = Display.FLEX,
    val direction: Direction = Direction.ROW,
    val wrap: Boolean = false,
    val wrapReverse: Boolean = false,
    val justifyContent: Distribute = Distribute.START,
    val alignItems: Align = Align.STRETCH,
    val alignContent: Distribute = Distribute.STRETCH,
    val justifyItems: Align = Align.STRETCH,
    val rowGap: Float = 0f,
    val columnGap: Float = 0f,
    val columns: List<GridTracks.Track> = emptyList(),
    val rows: List<GridTracks.Track> = emptyList(),
    /** Padding (+ border) around the content box; absolutely positioned children are placed in the padding box. */
    val paddingLeft: Float = 0f,
    val paddingTop: Float = 0f,
    val paddingRight: Float = 0f,
    val paddingBottom: Float = 0f,
) {
    /** Converts gaps, insets and fixed track sizes to device pixels. */
    fun scaled(factor: Float): ContainerStyle {
        fun Length.s(): Length = when (this) {
            is Length.Px -> Length.Px(px * factor)
            is Length.Calc -> Length.Calc(px * factor, percent)
            else -> this
        }
        fun GridTracks.Track.s(): GridTracks.Track = when (this) {
            is GridTracks.Track.Fixed -> GridTracks.Track.Fixed(length.s())
            is GridTracks.Track.MinMax -> GridTracks.Track.MinMax(min.s(), max.s())
            is GridTracks.Track.AutoRepeat -> GridTracks.Track.AutoRepeat(tracks.map { it.s() }, fit)
            else -> this
        }
        return copy(
            rowGap = rowGap * factor, columnGap = columnGap * factor,
            columns = columns.map { it.s() }, rows = rows.map { it.s() },
            paddingLeft = paddingLeft * factor, paddingTop = paddingTop * factor,
            paddingRight = paddingRight * factor, paddingBottom = paddingBottom * factor,
        )
    }

    companion object {
        fun from(style: ComputedStyle, insetLeft: Float = 0f, insetTop: Float = 0f, insetRight: Float = 0f, insetBottom: Float = 0f): ContainerStyle {
            val display = when (style.keyword("display")) {
                "grid" -> Display.GRID
                "stack" -> Display.STACK
                else -> Display.FLEX
            }
            // `display: block` is a column that stretches its children, like normal flow.
            val direction = if (style.keyword("display") == "block") Direction.COLUMN else when (style.keyword("flex-direction")) {
                "row-reverse" -> Direction.ROW_REVERSE
                "column" -> Direction.COLUMN
                "column-reverse" -> Direction.COLUMN_REVERSE
                else -> Direction.ROW
            }
            return ContainerStyle(
                display = display,
                direction = direction,
                wrap = style.keyword("flex-wrap") != "nowrap",
                wrapReverse = style.keyword("flex-wrap") == "wrap-reverse",
                justifyContent = distribute(style.keyword("justify-content"), Distribute.START),
                // Stacks default to the top-left corner (like a FrameLayout) rather than stretching.
                alignItems = if (display == Display.STACK && style.keyword("align-items") == "normal") Align.START else BoxStyle.align(style.keyword("align-items")) ?: Align.STRETCH,
                alignContent = distribute(style.keyword("align-content"), if (display == Display.GRID) Distribute.START else Distribute.STRETCH),
                justifyItems = if (display == Display.STACK && style.keyword("justify-items") == "normal") Align.START else BoxStyle.align(style.keyword("justify-items")) ?: Align.STRETCH,
                rowGap = style.px("row-gap").coerceAtLeast(0f),
                columnGap = style.px("column-gap").coerceAtLeast(0f),
                columns = GridTracks.parse(style.raw("grid-template-columns")) ?: emptyList(),
                rows = GridTracks.parse(style.raw("grid-template-rows")) ?: emptyList(),
                paddingLeft = insetLeft,
                paddingTop = insetTop,
                paddingRight = insetRight,
                paddingBottom = insetBottom,
            )
        }

        private fun distribute(keyword: String, normal: Distribute): Distribute = when (keyword) {
            "flex-end", "end", "right" -> Distribute.END
            "center" -> Distribute.CENTER
            "space-between" -> Distribute.SPACE_BETWEEN
            "space-around" -> Distribute.SPACE_AROUND
            "space-evenly" -> Distribute.SPACE_EVENLY
            "stretch" -> Distribute.STRETCH
            "normal" -> normal
            else -> Distribute.START
        }
    }
}

/** One child as the layout engine sees it. [measure] returns the child's border-box size under the constraints. */
interface LayoutChild {
    val style: BoxStyle
    fun measure(width: Constraint, height: Constraint): Size
}

/** Positions (relative to the content box's top-left) per child, in input order; null for `display: none`. */
data class LayoutResult(val contentWidth: Float, val contentHeight: Float, val rects: List<Rect?>)
