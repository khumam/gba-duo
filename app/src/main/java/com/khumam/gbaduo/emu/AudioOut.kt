package com.khumam.gbaduo.emu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

private const val SAMPLE_RATE = 32768

/** Streams interleaved stereo PCM16 from the core to the speaker. Call [pump] once per frame. */
class AudioOut {
    private val minBufferFrames = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
    ) / 4

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(minBufferFrames * 4 * 4)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val scratch = ShortArray(4096)
    private val stretch = AudioStretch()
    private val stretched = ShortArray(4096)
    private var lastSpeed = 1

    fun start() = track.play()
    fun stop() = track.stop()
    fun release() = track.release()

    /**
     * Drains available audio from the core and plays it. While the core runs [speed] times too fast, the audio is
     * stretched back to normal pitch (the music's tempo still follows the game); call it after every emulated frame.
     */
    fun pump(speed: Int = 1) {
        if (speed != lastSpeed) {
            stretch.reset()
            lastSpeed = speed
        }
        val frames = NativeCore.audio(scratch)
        if (frames <= 0) return
        if (speed <= 1) {
            track.write(scratch, 0, frames * 2)
            return
        }
        val out = stretch.process(scratch, frames, speed, stretched)
        // Never block the emulator on the speaker while fast-forwarding: what doesn't fit is dropped.
        if (out > 0) track.write(stretched, 0, out * 2, AudioTrack.WRITE_NON_BLOCKING)
    }
}
