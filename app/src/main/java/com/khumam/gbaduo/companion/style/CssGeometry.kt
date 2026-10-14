package com.khumam.gbaduo.companion.style

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Drawing math that follows the CSS specs, kept pure so it can be unit-tested. */
object CssGeometry {

    /** Start/end points of a `linear-gradient` line in a [width] x [height] box (CSS Images §3.1.1). */
    fun gradientLine(gradient: LinearGradient, width: Float, height: Float): FloatArray {
        val angle = gradient.angleDeg ?: gradient.toCorner?.let { (x, y) ->
            // "to <corner>": perpendicular to the diagonal joining the two other corners.
            Math.toDegrees(atan2((x * height).toDouble(), (-y * width).toDouble())).toFloat()
        } ?: 180f
        val rad = Math.toRadians(angle.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val length = abs(width * dx) + abs(height * dy)
        val cx = width / 2f
        val cy = height / 2f
        return floatArrayOf(cx - dx * length / 2f, cy - dy * length / 2f, cx + dx * length / 2f, cy + dy * length / 2f)
    }

    /** Stop positions (0..1) with missing ones filled in like CSS: first 0, last 1, gaps spread evenly, never decreasing. */
    fun stopPositions(stops: List<GradientStop>): FloatArray {
        val n = stops.size
        val pos = FloatArray(n) { stops[it].position ?: Float.NaN }
        if (pos[0].isNaN()) pos[0] = 0f
        if (pos[n - 1].isNaN()) pos[n - 1] = 1f
        var maxSoFar = pos[0]
        for (i in 1 until n) {
            if (!pos[i].isNaN()) {
                if (pos[i] < maxSoFar) pos[i] = maxSoFar
                maxSoFar = pos[i]
            }
        }
        var i = 1
        while (i < n) {
            if (pos[i].isNaN()) {
                val start = i - 1
                var end = i
                while (pos[end].isNaN()) end++
                for (k in start + 1 until end) pos[k] = pos[start] + (pos[end] - pos[start]) * (k - start) / (end - start)
                i = end
            }
            i++
        }
        return pos
    }

    /**
     * Corner radii [tl, tr, br, bl], scaled down together when adjacent radii
     * don't fit their side (CSS Backgrounds §5.5).
     */
    fun fitRadii(radii: FloatArray, width: Float, height: Float): FloatArray {
        val (tl, tr, br, bl) = radii.toList()
        var factor = 1f
        fun check(sum: Float, side: Float) {
            if (sum > 0f && sum > side) factor = minOf(factor, side / sum)
        }
        check(tl + tr, width)
        check(bl + br, width)
        check(tl + bl, height)
        check(tr + br, height)
        return floatArrayOf(tl * factor, tr * factor, br * factor, bl * factor)
    }
}
