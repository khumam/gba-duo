package com.khumam.gbaduo.emu

import kotlin.math.PI
import kotlin.math.cos

/**
 * Plays audio that was produced [speed] times too fast at normal pitch: overlapping Hann-windowed grains are read
 * from the input every `speed * HOP` frames and laid down every `HOP` frames, so about 1/speed of it is kept.
 * Streaming: feed whatever the core produced, take whatever is ready. Interleaved stereo PCM16.
 */
class AudioStretch {
    private var input = ShortArray(WINDOW * 2 * 8)
    private var inputFrames = 0
    private val overlap = FloatArray(WINDOW * 2)
    private var started = false
    private var pendingSkip = 0

    private val window = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / WINDOW)).toFloat() }

    fun reset() {
        inputFrames = 0
        overlap.fill(0f)
        started = false
        pendingSkip = 0
    }

    /** Adds [frames] frames of [samples]; returns how many frames were written to [out] (at most its capacity). */
    fun process(samples: ShortArray, frames: Int, speed: Int, out: ShortArray): Int {
        // Input the last grain's step jumped past before it had arrived.
        val skipped = minOf(pendingSkip, frames)
        pendingSkip -= skipped
        val fresh = frames - skipped
        if (input.size < (inputFrames + fresh) * 2) input = input.copyOf((inputFrames + fresh) * 2 + WINDOW * 2)
        System.arraycopy(samples, skipped * 2, input, inputFrames * 2, fresh * 2)
        inputFrames += fresh

        val step = speed * HOP
        var consumed = 0
        var produced = 0
        while (inputFrames - consumed >= WINDOW && (produced + HOP) * 2 <= out.size) {
            val base = consumed * 2
            for (i in 0 until WINDOW) {
                overlap[i * 2] += input[base + i * 2] * window[i]
                overlap[i * 2 + 1] += input[base + i * 2 + 1] * window[i]
            }
            // The first grain has nothing before it to fade in over, so it is not played until the next one lands on it.
            if (started) {
                for (i in 0 until HOP * 2) out[produced * 2 + i] = overlap[i].toInt().coerceIn(-32768, 32767).toShort()
                produced += HOP
            }
            started = true
            System.arraycopy(overlap, HOP * 2, overlap, 0, (WINDOW - HOP) * 2)
            overlap.fill(0f, (WINDOW - HOP) * 2, WINDOW * 2)
            consumed += step
        }
        if (consumed > 0) {
            pendingSkip += (consumed - inputFrames).coerceAtLeast(0)
            val kept = (inputFrames - consumed).coerceAtLeast(0)
            if (kept > 0) System.arraycopy(input, consumed * 2, input, 0, kept * 2)
            inputFrames = kept
        }
        return produced
    }

    companion object {
        const val WINDOW = 1024
        const val HOP = WINDOW / 2
    }
}
