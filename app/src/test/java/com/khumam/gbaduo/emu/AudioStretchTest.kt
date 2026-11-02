package com.khumam.gbaduo.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioStretchTest {

    /** [seconds] of a 440 Hz stereo tone at 32768 Hz, fed in per-frame chunks the way the core produces them. */
    private fun run(speed: Int, seconds: Int): ShortArray {
        val stretch = AudioStretch()
        val perChunk = 547 * speed
        val out = ArrayList<Short>()
        val chunk = ShortArray(perChunk * 2)
        val scratch = ShortArray(4096)
        var t = 0
        repeat(60 * seconds) {
            for (i in 0 until perChunk) {
                val v = (sin(2 * PI * 440 * t++ / 32768.0) * 12000).toInt().toShort()
                chunk[i * 2] = v; chunk[i * 2 + 1] = v
            }
            val n = stretch.process(chunk, perChunk, speed, scratch)
            for (i in 0 until n * 2) out.add(scratch[i])
        }
        return out.toShort()
    }

    private fun List<Short>.toShort() = ShortArray(size) { this[it] }

    @Test fun keepsAboutOneOverSpeedOfTheInput() {
        val frames = run(speed = 4, seconds = 4).size / 2
        // The core made 4 s * 4 of audio per 4 s of wall time; roughly 4 s of it should come out.
        assertEquals(4 * 32768.0, frames.toDouble(), 32768 * 0.15)
    }

    @Test fun staysInRangeAndIsNotSilent() {
        val out = run(speed = 3, seconds = 2)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 6000..13000)
    }
}
