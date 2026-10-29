package com.khumam.gbaduo.emu

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class ViewportSize(val width: Int, val height: Int)

/**
 * Where the game picture goes on the screen: its shape from [AspectRatio], optionally whole-number scaled.
 * [AspectRatio.STRETCH] fills the screen edge to edge, so its pixels are not square. With [integer] on it
 * rounds each axis down on its own, so it leaves thin bars where the screen isn't a whole multiple of the game.
 */
object ScreenFit {
    fun viewport(
        surfaceWidth: Int, surfaceHeight: Int, videoWidth: Int, videoHeight: Int,
        aspect: AspectRatio, integer: Boolean,
    ): ViewportSize {
        val shape = when (aspect) {
            AspectRatio.ORIGINAL -> videoWidth.toFloat() / videoHeight
            AspectRatio.STRETCH -> surfaceWidth.toFloat() / surfaceHeight
            else -> aspect.ratio!!
        }
        // Largest box of that shape inside the surface.
        val box =
            if (surfaceWidth / shape <= surfaceHeight) ViewportSize(surfaceWidth, (surfaceWidth / shape).roundToInt())
            else ViewportSize((surfaceHeight * shape).roundToInt(), surfaceHeight)
        if (!integer) return box
        // Each axis on its own, so every game pixel is the same size even when the shape isn't the game's.
        val scaleX = maxOf(1, floor(box.width.toFloat() / videoWidth).toInt())
        val scaleY = maxOf(1, floor(box.height.toFloat() / videoHeight).toInt())
        return ViewportSize(videoWidth * scaleX, videoHeight * scaleY)
    }

    /**
     * Whole-number factor to draw the game at before shrinking it to [viewport]: the smallest one that is not
     * smaller than the viewport on either axis. Shrinking from it keeps every game pixel an even block.
     */
    fun prescale(viewport: ViewportSize, videoWidth: Int, videoHeight: Int): Int =
        maxOf(1, ceil(maxOf(viewport.width.toFloat() / videoWidth, viewport.height.toFloat() / videoHeight)).toInt())

    /** True when [viewport] is a whole multiple of the game on both axes: nothing to even out. */
    fun isWholeNumber(viewport: ViewportSize, videoWidth: Int, videoHeight: Int): Boolean =
        viewport.width % videoWidth == 0 && viewport.height % videoHeight == 0
}
