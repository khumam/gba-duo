package com.khumam.gbaduo.settings

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.controls.Binding
import com.khumam.gbaduo.controls.KeyCapture
import com.khumam.gbaduo.library.ui.Pixel

/**
 * A settings page that can ask "Press a button…". While a capture runs, key events from
 * the built-in controller go to the [KeyCapture]; the page's own A/B/D-pad handling is
 * paused, and events from other devices are swallowed. Back or a 2 second hold cancels.
 */
abstract class CapturePage : PixelPage() {

    private var capture: KeyCapture? = null
    private var onDone: ((Binding?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val c = capture ?: return
            if (!finish(c.onTick(SystemClock.uptimeMillis()))) handler.postDelayed(this, 100)
        }
    }

    /** A line under the page's pieces for prompts and results; set up by the page. */
    protected lateinit var status: TextView

    protected fun addStatus(top: Int = 12) {
        status = note("", top = top).apply { visibility = android.view.View.GONE }
    }

    protected fun showStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        status.visibility = android.view.View.VISIBLE
    }

    /** [done] gets the captured binding, or null if it was cancelled. */
    protected fun beginCapture(capture: KeyCapture, done: (Binding?) -> Unit) {
        this.capture = capture
        onDone = done
        showStatus(getString(R.string.controls_press))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 100)
    }

    private fun finish(outcome: KeyCapture.Outcome): Boolean {
        val result = when (outcome) {
            KeyCapture.Outcome.Waiting -> return false
            KeyCapture.Outcome.Cancelled -> null
            is KeyCapture.Outcome.Captured -> outcome.binding
        }
        val done = onDone
        capture = null
        onDone = null
        handler.removeCallbacks(tick)
        status.visibility = android.view.View.GONE
        done?.invoke(result)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = capture ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.repeatCount > 0 || event.device?.isExternal == true) return true
        finish(c.onKey(event.keyCode, down, event.eventTime))
        return true
    }

    override fun onPause() {
        capture = null
        onDone = null
        handler.removeCallbacks(tick)
        super.onPause()
    }
}
