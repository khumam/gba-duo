package com.khumam.gbaduo.settings

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.controls.Binding
import com.khumam.gbaduo.controls.KeyCapture
import com.khumam.gbaduo.library.ui.Pixel

/**
 * A settings page that can ask "Press a button…". While a capture runs, key events go to
 * the [KeyCapture] and the page's own A/B/D-pad handling is paused. Back or a 2 second hold
 * cancels. Devices aren't told apart: the Thor's own controller reports itself as external,
 * so filtering on that would ignore the very buttons being mapped.
 */
abstract class CapturePage : PixelPage() {

    private var capture: KeyCapture? = null
    private var onDone: ((Binding?) -> Unit)? = null
    private var overlay: View? = null
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
        // The page can be scrolled far from the status line, so say it where it can't be missed too.
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        status.text = text
        status.setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
        status.visibility = android.view.View.VISIBLE
    }

    /** [done] gets the captured binding, or null if it was cancelled. [what] names what is being set. */
    protected fun beginCapture(capture: KeyCapture, what: String, done: (Binding?) -> Unit) {
        this.capture = capture
        onDone = done
        showPrompt(what)
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
        hidePrompt()
        done?.invoke(result)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = capture ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.repeatCount > 0) return true
        finish(c.onKey(event.keyCode, down, event.eventTime))
        return true
    }

    /** A modal over the page, wherever it is scrolled: this is the moment to press the button. */
    private fun showPrompt(what: String) {
        hidePrompt()
        val box = buildDialogBox(getString(R.string.controls_remap_tab)) {
            addView(boxText(getString(R.string.controls_press_title, what), 20f))
            addView(boxText(getString(R.string.controls_press_hint), 13f, top = 12))
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xCC120C26.toInt())
            isClickable = true
            // A tap outside the box backs out, like Back.
            setOnClickListener { if (capture != null) finish(KeyCapture.Outcome.Cancelled) }
        }
        scrim.addView(box, FrameLayout.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        (window.decorView as ViewGroup).addView(scrim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlay = scrim
    }

    private fun hidePrompt() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    override fun onPause() {
        hidePrompt()
        capture = null
        onDone = null
        handler.removeCallbacks(tick)
        super.onPause()
    }
}
