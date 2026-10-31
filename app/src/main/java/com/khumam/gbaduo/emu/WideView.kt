package com.khumam.gbaduo.emu

import kotlin.math.max

/** How wide a picture the core should draw so a GBA game's own pixels fill the screen, square. */
object WideView {
    const val FRAME_WIDTH = 240
    const val FRAME_HEIGHT = 160
    const val MAX_WIDTH = 400

    /** What [EmuThread] asks the core for: the game's own 240 unless Wide view is on and the pack can paint it. */
    fun wantedWidth(enabled: Boolean, hasWideHook: Boolean, surfaceW: Int, surfaceH: Int, integer: Boolean): Int =
        if (enabled && hasWideHook) widthFor(surfaceW, surfaceH, integer) else FRAME_WIDTH

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
}
