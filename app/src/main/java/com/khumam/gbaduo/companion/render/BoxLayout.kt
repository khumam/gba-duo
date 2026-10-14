package com.khumam.gbaduo.companion.render

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.khumam.gbaduo.companion.layout.BoxStyle
import com.khumam.gbaduo.companion.layout.Constraint
import com.khumam.gbaduo.companion.layout.ContainerStyle
import com.khumam.gbaduo.companion.layout.LayoutChild
import com.khumam.gbaduo.companion.layout.LayoutEngine
import com.khumam.gbaduo.companion.layout.Rect
import com.khumam.gbaduo.companion.layout.Size
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Layout params every child of a [BoxLayout] carries: its CSS box (in device px) and stacking order. */
class BoxParams : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) {
    var box: BoxStyle = BoxStyle.DEFAULT
    var zIndex: Int = 0
}

/** Views that report pressed-state changes, for `:pressed`. */
interface PressAware {
    var onPressedChanged: ((Boolean) -> Unit)?
}

/**
 * A container laid out by [LayoutEngine] (flex, grid, stack, absolute) from its
 * [container] style and each child's [BoxParams]. Children aren't clipped, so
 * box shadows can spill out, unless `overflow: hidden` asks for it.
 */
class BoxLayout(context: Context) : ViewGroup(context), PressAware {

    var container: ContainerStyle = ContainerStyle()
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override var onPressedChanged: ((Boolean) -> Unit)? = null
    private var wasPressed = false
    private var rects: List<Rect?> = emptyList()

    /** Called with the new size in device px (used by the viewport for `vw`/`vh`). */
    var onSizeChanged: ((Int, Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onSizeChanged?.invoke(w, h)
    }

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun generateDefaultLayoutParams(): LayoutParams = BoxParams()
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = BoxParams()
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is BoxParams

    private fun constraint(spec: Int): Constraint {
        val size = MeasureSpec.getSize(spec).toFloat()
        return when (MeasureSpec.getMode(spec)) {
            MeasureSpec.EXACTLY -> Constraint.Exactly(size)
            MeasureSpec.AT_MOST -> Constraint.AtMost(size)
            else -> Constraint.Unbounded
        }
    }

    private fun spec(c: Constraint): Int = when (c) {
        is Constraint.Exactly -> MeasureSpec.makeMeasureSpec(c.px.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
        is Constraint.AtMost -> MeasureSpec.makeMeasureSpec(c.px.toInt().coerceAtLeast(0), MeasureSpec.AT_MOST)
        Constraint.Unbounded -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    }

    private inner class ViewChild(val view: View) : LayoutChild {
        override val style: BoxStyle = (view.layoutParams as? BoxParams)?.box?.let { if (view.visibility == GONE) it.copy(displayNone = true) else it }
            ?: BoxStyle.DEFAULT.copy(displayNone = view.visibility == GONE)

        override fun measure(width: Constraint, height: Constraint): Size {
            view.measure(spec(width), spec(height))
            return Size(view.measuredWidth.toFloat(), view.measuredHeight.toFloat())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontal = (paddingLeft + paddingRight).toFloat()
        val vertical = (paddingTop + paddingBottom).toFloat()
        val children = (0 until childCount).map { ViewChild(getChildAt(it)) }
        val c = container.copy(
            paddingLeft = paddingLeft.toFloat(), paddingTop = paddingTop.toFloat(),
            paddingRight = paddingRight.toFloat(), paddingBottom = paddingBottom.toFloat(),
        )
        val result = LayoutEngine.layout(c, constraint(widthMeasureSpec).shrink(horizontal), constraint(heightMeasureSpec).shrink(vertical), children)
        rects = result.rects
        // Final pass: every child gets exactly the box the engine gave it.
        for ((i, child) in children.withIndex()) {
            val r = result.rects[i] ?: continue
            child.view.measure(
                MeasureSpec.makeMeasureSpec(r.width.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(
            resolve(widthMeasureSpec, ceil(result.contentWidth + horizontal).toInt()),
            resolve(heightMeasureSpec, ceil(result.contentHeight + vertical).toInt()),
        )
    }

    private fun resolve(spec: Int, content: Int): Int {
        val size = MeasureSpec.getSize(spec)
        return when (MeasureSpec.getMode(spec)) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> minOf(size, content)
            else -> content
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = rects.getOrNull(i) ?: continue
            if (child.visibility == GONE) continue
            val left = paddingLeft + rect.x.roundToInt()
            val top = paddingTop + rect.y.roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    /** Draw children in `z-index` order (stable), like CSS stacking within one container. */
    fun updateDrawingOrder() {
        isChildrenDrawingOrderEnabled = (0 until childCount).any { (getChildAt(it).layoutParams as? BoxParams)?.zIndex != 0 }
        invalidate()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        (0 until childCount).sortedBy { (getChildAt(it).layoutParams as? BoxParams)?.zIndex ?: 0 }[drawingPosition]

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isPressed != wasPressed) {
            wasPressed = isPressed
            onPressedChanged?.invoke(isPressed)
        }
    }
}

class StyledTextView(context: Context) : TextView(context), PressAware {
    override var onPressedChanged: ((Boolean) -> Unit)? = null
    private var wasPressed = false

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isPressed != wasPressed) {
            wasPressed = isPressed
            onPressedChanged?.invoke(isPressed)
        }
    }
}

class StyledImageView(context: Context) : ImageView(context), PressAware {
    override var onPressedChanged: ((Boolean) -> Unit)? = null
    private var wasPressed = false

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isPressed != wasPressed) {
            wasPressed = isPressed
            onPressedChanged?.invoke(isPressed)
        }
    }
}
