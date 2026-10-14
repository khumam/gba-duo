package com.khumam.gbaduo.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.khumam.gbaduo.companion.layout.BoxStyle
import com.khumam.gbaduo.companion.layout.Constraint
import com.khumam.gbaduo.companion.layout.ContainerStyle
import com.khumam.gbaduo.companion.layout.LayoutChild
import com.khumam.gbaduo.companion.layout.LayoutEngine
import com.khumam.gbaduo.companion.layout.LayoutResult
import com.khumam.gbaduo.companion.layout.Size
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A child's CSS box (device px) and stacking order, read by its parent [CssLayout]. */
internal class CssParentData(val box: BoxStyle, val zIndex: Int) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@CssParentData
}

/** Padding + border around the content box, in device px. */
internal data class Insets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val horizontal get() = left + right
    val vertical get() = top + bottom
}

/** Lays out its children with [LayoutEngine] (flex, grid, stack, absolute) per [container]. */
@Composable
internal fun CssLayout(container: ContainerStyle, insets: Insets, modifier: Modifier, content: @Composable () -> Unit) {
    val policy = remember(container, insets) { CssMeasurePolicy(container, insets) }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

/**
 * Bridges Compose layout and [LayoutEngine]. The engine probes children's sizes
 * several times; Compose allows measuring a child only once, so probes use the
 * child's intrinsic sizes and only the engine's final box is a real measure.
 */
internal class CssMeasurePolicy(private val container: ContainerStyle, private val insets: Insets) : MeasurePolicy {

    private class IntrinsicChild(private val measurable: IntrinsicMeasurable) : LayoutChild {
        private val data = measurable.parentData as? CssParentData
        override val style: BoxStyle = data?.box ?: BoxStyle.DEFAULT
        private val memo = HashMap<Pair<Constraint, Constraint>, Size>()

        override fun measure(width: Constraint, height: Constraint): Size = memo.getOrPut(width to height) {
            val heightHint = height.definite?.roundToInt()?.coerceAtLeast(0) ?: Constraints.Infinity
            val w = when (width) {
                is Constraint.Exactly -> width.px
                is Constraint.AtMost -> minOf(measurable.maxIntrinsicWidth(heightHint).toFloat(), width.px)
                Constraint.Unbounded -> measurable.maxIntrinsicWidth(heightHint).toFloat()
            }
            val widthHint = w.roundToInt().coerceAtLeast(0)
            val h = when (height) {
                is Constraint.Exactly -> height.px
                is Constraint.AtMost -> minOf(measurable.maxIntrinsicHeight(widthHint).toFloat(), height.px)
                Constraint.Unbounded -> measurable.maxIntrinsicHeight(widthHint).toFloat()
            }
            Size(w, h)
        }
    }

    private fun run(children: List<IntrinsicMeasurable>, width: Constraint, height: Constraint): LayoutResult {
        val c = container.copy(paddingLeft = insets.left, paddingTop = insets.top, paddingRight = insets.right, paddingBottom = insets.bottom)
        return LayoutEngine.layout(c, width, height, children.map { IntrinsicChild(it) })
    }

    private fun widthConstraint(c: Constraints): Constraint = when {
        c.hasFixedWidth -> Constraint.Exactly(c.maxWidth.toFloat())
        c.hasBoundedWidth -> Constraint.AtMost(c.maxWidth.toFloat())
        else -> Constraint.Unbounded
    }.shrink(insets.horizontal)

    private fun heightConstraint(c: Constraints): Constraint = when {
        c.hasFixedHeight -> Constraint.Exactly(c.maxHeight.toFloat())
        c.hasBoundedHeight -> Constraint.AtMost(c.maxHeight.toFloat())
        else -> Constraint.Unbounded
    }.shrink(insets.vertical)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val result = run(measurables, widthConstraint(constraints), heightConstraint(constraints))
        val placeables = measurables.mapIndexed { i, measurable ->
            result.rects[i]?.let { r ->
                val placeable = measurable.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)))
                Triple(placeable, r, (measurable.parentData as? CssParentData)?.zIndex ?: 0)
            }
        }
        val width = ceil(result.contentWidth + insets.horizontal).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(result.contentHeight + insets.vertical).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            for (entry in placeables) {
                val (placeable, r, z) = entry ?: continue
                placeable.place((insets.left + r.x).roundToInt(), (insets.top + r.y).roundToInt(), z.toFloat())
            }
        }
    }

    private fun intrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val h = if (height == Constraints.Infinity) Constraint.Unbounded else Constraint.Exactly(height.toFloat()).shrink(insets.vertical)
        return ceil(run(measurables, Constraint.Unbounded, h).contentWidth + insets.horizontal).toInt()
    }

    private fun intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val w = if (width == Constraints.Infinity) Constraint.Unbounded else Constraint.Exactly(width.toFloat()).shrink(insets.horizontal)
        return ceil(run(measurables, w, Constraint.Unbounded).contentHeight + insets.vertical).toInt()
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = intrinsicWidth(measurables, height)
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = intrinsicWidth(measurables, height)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = intrinsicHeight(measurables, width)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = intrinsicHeight(measurables, width)
}
