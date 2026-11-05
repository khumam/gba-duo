package com.khumam.gbaduo.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileSpanTest {

    // Tiles are laid out the way the painter does it: each one starts at the previous plus the size.
    private fun tiles(start: Float, size: Float, count: Int) = generateSequence(start) { it + size }.take(count).map { tileSpan(it, size) }.toList()

    @Test
    fun fractionalTilesShareEdges() {
        // 10.4px tiles: rounding the size alone would give 10px tiles with 1px gaps.
        val size = 10.4f
        val spans = tiles(0f, size, 20)
        for ((a, b) in spans.zipWithNext()) assertEquals(a.first + a.second, b.first)
        assertEquals(208, spans.last().first + spans.last().second) // ends where 20 tiles should
    }

    @Test
    fun negativeStartsShareEdgesToo() {
        val size = 7.6f
        val spans = tiles(-25.3f, size, 6)
        for ((a, b) in spans.zipWithNext()) assertEquals(a.first + a.second, b.first)
    }

    @Test
    fun wholePixelTilesAreUnchanged() {
        assertEquals(16 to 8, tileSpan(16f, 8f))
        assertEquals(-4 to 8, tileSpan(-4f, 8f))
    }
}
