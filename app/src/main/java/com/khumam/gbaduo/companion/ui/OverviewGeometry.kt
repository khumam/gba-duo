package com.khumam.gbaduo.companion.ui

import kotlin.math.max
import kotlin.math.roundToInt

/** The sizes behind a card's picture: decode it near card size, scale it to cover the card, keep its top and centre. */
object OverviewGeometry {

    /** The biggest power of two that still leaves the decoded picture at least [targetWidth] wide (1 = full size). */
    fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    /** The picture's size once scaled to cover [width] x [height] (width and height both met, aspect kept). */
    fun coverSize(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Pair<Int, Int> {
        val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        return (sourceWidth * scale).roundToInt() to (sourceHeight * scale).roundToInt()
    }

    /** Where the crop starts sideways: centred. It always starts at the top. */
    fun cropLeft(scaledWidth: Int, width: Int): Int = (scaledWidth - width) / 2
}
