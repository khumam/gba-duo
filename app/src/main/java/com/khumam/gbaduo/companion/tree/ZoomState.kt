package com.khumam.gbaduo.companion.tree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.khumam.gbaduo.companion.ZoomSpec

/**
 * A zoomable box's view (`"zoom"` in layout.json): how much its content is scaled and where it is panned to, in
 * device px, with the content's top left as the scale origin. Pinch and drag change it directly; the UI key
 * [ZoomSpec.state] holds the scale so the `zoom` action (+ / - buttons) and the pack's Lua see the same value. The
 * content never leaves the box: the pan is clamped so its edges stay at or beyond the box's.
 */
class ZoomState(val spec: ZoomSpec, private val commit: (Float) -> Unit) {
    var scale by mutableFloatStateOf(spec.min)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The box's size in device px (set by the screen as it lays the box out). */
    var size: IntSize = IntSize.Zero
        set(value) {
            field = value
            offset = clamp(offset, scale)
        }

    /** Fingers are down: the UI key's (older) value must not pull the view back meanwhile. */
    private var gesturing = false

    /** A pinch / drag step: zoom by [factor] around [centroid] (box px), then move by [pan]. */
    fun gesture(centroid: Offset, pan: Offset, factor: Float) {
        gesturing = true
        val next = (scale * factor).coerceIn(spec.min, spec.max)
        val f = next / scale
        val moved = centroid - (centroid - offset) * f + pan
        scale = next
        offset = clamp(moved, next)
    }

    /** The fingers lifted: the UI key takes the new scale (rounded, so it reads well in Lua). */
    fun gestureEnd() {
        if (!gesturing) return
        gesturing = false
        commit((scale * 100f).toInt() / 100f)
    }

    /** The UI key changed (a button, a `set`, the script): zoom to [value] around the middle of the box. */
    fun setTarget(value: Float) {
        if (gesturing) return
        val next = value.coerceIn(spec.min, spec.max)
        if (kotlin.math.abs(next - scale) < 0.005f) return
        val centre = Offset(size.width / 2f, size.height / 2f)
        val f = next / scale
        val moved = centre - (centre - offset) * f
        scale = next
        offset = clamp(moved, next)
    }

    private fun clamp(o: Offset, s: Float): Offset {
        val minX = size.width - size.width * s
        val minY = size.height - size.height * s
        return Offset(o.x.coerceIn(minOf(minX, 0f), 0f), o.y.coerceIn(minOf(minY, 0f), 0f))
    }
}
