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

    fun start() = track.play()
    fun stop() = track.stop()
    fun release() = track.release()

    /** Drains available audio from the core; writes it unless [mute] (still drained, to avoid buffer buildup). */
    fun pump(mute: Boolean) {
        val frames = NativeCore.audio(scratch)
        if (frames <= 0 || mute) return
        track.write(scratch, 0, frames * 2)
    }
}
