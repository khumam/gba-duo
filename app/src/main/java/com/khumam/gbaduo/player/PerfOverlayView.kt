package com.khumam.gbaduo.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.khumam.gbaduo.emu.PerfMeter
import com.khumam.gbaduo.emu.PerfOverlay
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel

/** FPS and CPU readout in the top-left corner of the game, refreshed once a second. Main thread only. */
class PerfOverlayView(
    private val container: FrameLayout,
    private val mode: PerfOverlay,
    private val frames: () -> Long,
) {
    private val context = container.context
    private val meter = PerfMeter()
    private var view: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            val line = meter.sample(SystemClock.elapsedRealtimeNanos(), frames(), Process.getElapsedCpuTime(), mode)
            if (line != null) view?.text = line
            view?.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).toInt()

    fun attach() {
        if (mode == PerfOverlay.OFF || view != null) return
        val text = TextView(context).apply {
            typeface = Fonts.body(context, 600)
            textSize = 12f
            setTextColor(Pixel.ON_INK)
            // PixelBoxDrawable ignores alpha, so a plain translucent panel keeps the game visible behind it.
            background = GradientDrawable().apply {
                setColor(Color.argb(PANEL_ALPHA, Color.red(Pixel.INK), Color.green(Pixel.INK), Color.blue(Pixel.INK)))
            }
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        container.addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(16f), dp(16f), 0, 0) })
        view = text
        meter.reset()
        text.postDelayed(tick, INTERVAL_MS)
    }

    fun detach() {
        view?.let {
            it.removeCallbacks(tick)
            container.removeView(it)
        }
        view = null
    }

    private companion object {
        const val INTERVAL_MS = 1000L
        const val PANEL_ALPHA = 90
    }
}
