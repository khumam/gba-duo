package com.khumam.gbaduo.emu

import android.content.Context

/** What the in-game performance readout shows (Settings > Performance overlay). */
enum class PerfOverlay(val id: String, val displayName: String, val showFps: Boolean, val showCpu: Boolean) {
    OFF("off", "Off", false, false),
    FPS("fps", "FPS", true, false),
    FPS_CPU("fps_cpu", "FPS + CPU", true, true),
    ;

    companion object {
        fun byId(id: String?): PerfOverlay = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The performance readout every game uses. Off until the player chooses one. */
object PerfOverlaySettings {
    private const val PREFS = "perf_overlay"
    private const val KEY_DEFAULT = "default"

    fun default(context: Context): PerfOverlay =
        PerfOverlay.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null))

    fun setDefault(context: Context, overlay: PerfOverlay) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, overlay.id).apply()
    }
}

/**
 * Turns two running counters into a readout line. [sample] gets the total frames the core has run and is
 * called about once a second; FPS is frames per wall second (above 60 while fast-forwarding), CPU is this
 * app's share of all cores over the same span.
 */
class PerfMeter(private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) {
    private var lastNanos = 0L
    private var lastFrames = 0L
    private var lastCpuMillis = 0L

    fun reset() {
        lastNanos = 0L
    }

    /** Null on the first call (nothing to compare with). */
    fun sample(nowNanos: Long, frames: Long, cpuMillis: Long, mode: PerfOverlay): String? {
        val first = lastNanos == 0L
        val seconds = (nowNanos - lastNanos) / 1e9
        val fps = (frames - lastFrames) / seconds
        val cpu = (cpuMillis - lastCpuMillis) / (seconds * 1000.0 * cores) * 100.0
        lastNanos = nowNanos
        lastFrames = frames
        lastCpuMillis = cpuMillis
        if (first || seconds <= 0.0) return null
        return buildList {
            if (mode.showFps) add("FPS %.0f".format(fps))
            if (mode.showCpu) add("CPU %.0f%%".format(cpu.coerceIn(0.0, 100.0)))
        }.joinToString("  ")
    }
}
