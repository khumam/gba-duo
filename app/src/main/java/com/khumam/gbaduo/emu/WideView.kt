package com.khumam.gbaduo.emu

import com.khumam.gbaduo.companion.WideMode
import kotlin.math.max

/** The size of the picture the core draws, in game pixels. */
data class WideSize(val width: Int, val height: Int)

/** How big a picture the core should draw so a GBA game's own pixels fill the screen, square. */
object WideView {
    const val FRAME_WIDTH = 240
    const val FRAME_HEIGHT = 160
    const val MAX_WIDTH = 400

    /** Extra wide: the game's frame drawn this many rows tall (at most) on screen, the rest filled with the world. */
    const val EXTRA_ROWS = 240
    const val EXTRA_MAX_WIDTH = 480
    const val EXTRA_MAX_HEIGHT = 320

    val OFF = WideSize(FRAME_WIDTH, FRAME_HEIGHT)

    /** What [EmuThread] asks the core for: the game's own 240x160 unless Wide view is on and the pack can paint it. */
    fun wantedSize(mode: WideMode, hasWideHook: Boolean, surfaceW: Int, surfaceH: Int, integer: Boolean): WideSize =
        when {
            !hasWideHook -> OFF
            mode == WideMode.WIDE -> WideSize(widthFor(surfaceW, surfaceH, integer), FRAME_HEIGHT)
            mode == WideMode.EXTRA -> extraSizeFor(surfaceW, surfaceH, integer)
            else -> OFF
        }

    /**
     * The picture width for a [surfaceW] x [surfaceH] screen: the game's 160 rows scaled to the screen's
     * height (a whole number of times with [integer] on), then as many columns as fit across. Even, and
     * kept between the game's own 240 and [MAX_WIDTH].
     */
    fun widthFor(surfaceW: Int, surfaceH: Int, integer: Boolean): Int {
        if (surfaceW <= 0 || surfaceH <= 0) return FRAME_WIDTH
        val width = if (integer) {
            surfaceW / max(1, surfaceH / FRAME_HEIGHT)
        } else {
            surfaceW * FRAME_HEIGHT / surfaceH
        }
        return (width and 1.inv()).coerceIn(FRAME_WIDTH, MAX_WIDTH)
    }

    /**
     * Extra wide for a [surfaceW] x [surfaceH] screen: the picture has the screen's shape and shows about
     * [EXTRA_ROWS] rows (a whole number of screen pixels per game pixel with [integer] on), so the game's frame
     * sits in the middle at two thirds the size and the world fills the rest. Even, between the game's own
     * 240x160 and [EXTRA_MAX_WIDTH] x [EXTRA_MAX_HEIGHT]. A screen narrower than the frame keeps the 240 columns
     * and grows taller instead.
     */
    fun extraSizeFor(surfaceW: Int, surfaceH: Int, integer: Boolean): WideSize {
        if (surfaceW <= 0 || surfaceH <= 0) return OFF
        var width: Int
        var height: Int
        if (integer) {
            val scale = max(1, surfaceH / EXTRA_ROWS)
            width = surfaceW / scale
            height = surfaceH / scale
        } else {
            height = EXTRA_ROWS
            width = surfaceW * EXTRA_ROWS / surfaceH
        }
        if (width < FRAME_WIDTH) {
            width = FRAME_WIDTH
            height = surfaceH * FRAME_WIDTH / surfaceW
        }
        return WideSize(
            (width and 1.inv()).coerceIn(FRAME_WIDTH, EXTRA_MAX_WIDTH),
            (height and 1.inv()).coerceIn(FRAME_HEIGHT, EXTRA_MAX_HEIGHT),
        )
    }
}

/**
 * When Wide view has nothing to show (a menu, a small room: no world, a world that doesn't match the frame, or a map
 * that leaves the margins mostly empty) the game goes back to its own picture, filling the screen as the aspect ratio
 * setting says, and returns to Wide view when a world is back. Both ways wait a while so a passing dip does not flap it.
 */
class WideFallback {
    var active = false
        private set
    private var unusable = 0
    private var clearlyWrong = 0
    private var dwell = 0

    fun reset() {
        active = false
        unusable = 0
        clearlyWrong = 0
        dwell = 0
    }

    /**
     * One frame's step with whether the margins show, how much of them the map covers and how well the world matched the
     * frame (0 to 1); returns whether to fall back. A match that is clearly wrong (a menu replacing the picture) falls back
     * within a few frames; a mere dip waits longer.
     */
    fun step(shown: Boolean, coverage: Float, match: Float = 1f): Boolean {
        dwell++
        if (!active) {
            unusable = if (!shown || coverage < LOW_COVERAGE) unusable + 1 else 0
            clearlyWrong = if (match < CLEARLY_WRONG_MATCH) clearlyWrong + 1 else 0
            val gone = unusable >= UNUSABLE_FRAMES || clearlyWrong >= CLEARLY_WRONG_FRAMES
            if (gone && dwell >= MIN_DWELL_FRAMES) switchTo(true)
        } else if (shown && coverage >= HIGH_COVERAGE && dwell >= MIN_DWELL_FRAMES) {
            switchTo(false)
        }
        return active
    }

    private fun switchTo(fallback: Boolean) {
        active = fallback
        unusable = 0
        clearlyWrong = 0
        dwell = 0
    }

    companion object {
        const val UNUSABLE_FRAMES = 10
        const val MIN_DWELL_FRAMES = 30
        const val CLEARLY_WRONG_MATCH = 0.5f
        const val CLEARLY_WRONG_FRAMES = 3
        const val LOW_COVERAGE = 0.15f
        const val HIGH_COVERAGE = 0.30f
    }
}
