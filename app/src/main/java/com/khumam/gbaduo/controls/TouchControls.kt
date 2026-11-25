package com.khumam.gbaduo.controls

import android.content.Context
import com.khumam.gbaduo.emu.GbaKeys
import kotlin.math.atan2
import kotlin.math.hypot

/** Settings > Controller > Touch controller: off by default. Read as a game starts. */
object TouchControlsSettings {
    private const val PREFS = "controls"
    private const val KEY = "touch_overlay"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * Where the on-screen GBA buttons sit on a landscape screen of [width] × [height] px, and
 * which `GbaKeys` bits a touch at a point presses. Pure (no Android types), so it is unit tested.
 *
 * D-pad bottom-left, A and B bottom-right (A higher, as on the GBA), L and R in the top
 * corners, Select and Start along the bottom middle, and a small menu button under L.
 * [density] is px per dp.
 */
class TouchLayout(val width: Float, val height: Float, density: Float) {

    /** A round button. */
    data class Round(val bits: Int, val label: String, val cx: Float, val cy: Float, val r: Float)

    /** A rounded-rectangle button (shoulders, Select/Start). */
    data class Pill(val bits: Int, val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
    }

    val margin = 20f * density
    /** Face-button radius. */
    val r = (height * 0.075f).coerceIn(26f * density, 40f * density)

    /** D-pad: centre and half the cross's span. */
    val dpadR = r * 2.3f
    val dpadX = margin + dpadR
    val dpadY = height - margin - dpadR

    val a = Round(GbaKeys.A, "A", width - margin - r, dpadY - r * 0.75f, r)
    val b = Round(GbaKeys.B, "B", width - margin - r * 3.4f, dpadY + r * 0.75f, r)
    val menu = Round(MENU, "≡", margin + r * 0.7f, margin + r * 1.5f + r * 0.6f + 12f * density, r * 0.6f)

    private val shoulderW = r * 3.4f
    private val shoulderH = r * 1.5f
    val l = Pill(GbaKeys.L, "L", margin, margin, margin + shoulderW, margin + shoulderH)
    val rShoulder = Pill(GbaKeys.R, "R", width - margin - shoulderW, margin, width - margin, margin + shoulderH)

    private val smallW = r * 2.2f
    private val smallH = r * 0.85f
    private val smallGap = r * 0.4f
    private val smallBottom = height - margin * 0.6f
    val select = Pill(GbaKeys.SELECT, "SELECT", width / 2 - smallGap / 2 - smallW, smallBottom - smallH, width / 2 - smallGap / 2, smallBottom)
    val start = Pill(GbaKeys.START, "START", width / 2 + smallGap / 2, smallBottom - smallH, width / 2 + smallGap / 2 + smallW, smallBottom)

    val rounds = listOf(a, b, menu)
    val pills = listOf(l, rShoulder, select, start)

    /** The bits a finger at ([x], [y]) presses, including [MENU]; 0 for empty space. */
    fun hit(x: Float, y: Float): Int {
        dpad(x, y).let { if (it != 0) return it }
        val onA = hypot(x - a.cx, y - a.cy) <= a.r * ROUND_SLOP
        val onB = hypot(x - b.cx, y - b.cy) <= b.r * ROUND_SLOP
        if (onA || onB) return (if (onA) a.bits else 0) or (if (onB) b.bits else 0)
        if (hypot(x - menu.cx, y - menu.cy) <= menu.r * ROUND_SLOP) return MENU
        val pad = r * 0.3f
        for (p in pills) {
            if (x >= p.left - pad && x <= p.right + pad && y >= p.top - pad && y <= p.bottom + pad) return p.bits
        }
        return 0
    }

    /** Eight-way: each direction owns a 45° slice, so diagonals are two bits. Nothing near the very centre. */
    private fun dpad(x: Float, y: Float): Int {
        val dx = x - dpadX
        val dy = y - dpadY
        val dist = hypot(dx, dy)
        if (dist > dpadR * DPAD_REACH || dist < dpadR * DPAD_DEAD) return 0
        // 0° = right, counter-clockwise, screen y flipped so up is positive.
        var deg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        if (deg < 0) deg += 360.0
        return when (((deg + 22.5) / 45.0).toInt() % 8) {
            0 -> GbaKeys.RIGHT
            1 -> GbaKeys.RIGHT or GbaKeys.UP
            2 -> GbaKeys.UP
            3 -> GbaKeys.UP or GbaKeys.LEFT
            4 -> GbaKeys.LEFT
            5 -> GbaKeys.LEFT or GbaKeys.DOWN
            6 -> GbaKeys.DOWN
            else -> GbaKeys.DOWN or GbaKeys.RIGHT
        }
    }

    companion object {
        /** Not a GBA key: the button that opens the quick menu. */
        const val MENU = 1 shl 16
        const val GAME_BITS = 0x3FF

        private const val ROUND_SLOP = 1.25f
        private const val DPAD_REACH = 1.3f
        private const val DPAD_DEAD = 0.18f
    }
}
