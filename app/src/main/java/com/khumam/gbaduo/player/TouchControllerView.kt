package com.khumam.gbaduo.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.khumam.gbaduo.controls.TouchLayout
import com.khumam.gbaduo.emu.GbaKeys
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel

/**
 * The on-screen GBA controller over the game (Settings > Controller > Touch controller).
 * Multi-touch: every finger is hit-tested on each move, so a thumb can roll across the
 * D-pad or slide from B to A. What is held goes to [onKeys] as `GbaKeys` bits; the menu
 * button calls [onMenu] once per press. Translucent, so the game shows through.
 */
@SuppressLint("ViewConstructor")
class TouchControllerView(
    context: Context,
    private val onKeys: (Int) -> Unit,
    private val onMenu: () -> Unit,
) : View(context) {

    private var layout: TouchLayout? = null
    private val fingers = HashMap<Int, Int>()   // pointer id → bits under it
    private var held = 0

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = withAlpha(Pixel.LAVENDER, 150)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.display(context, bold = true)
        color = withAlpha(Pixel.LAVENDER, 210)
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout = TouchLayout(w.toFloat(), h.toFloat(), density)
    }

    /** Lifts every finger: for focus loss, a dialog opening or the view being hidden. */
    fun releaseAll() {
        fingers.clear()
        publish()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAll()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = layout ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val bits = l.hit(event.getX(i), event.getY(i))
                fingers[event.getPointerId(i)] = bits
                if (bits and TouchLayout.MENU != 0) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onMenu()
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                // The menu fires on the press only; a finger that slides off it then presses what it reaches.
                fingers[id] = l.hit(event.getX(i), event.getY(i)) and TouchLayout.MENU.inv()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> fingers.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> fingers.clear()
        }
        publish()
        return true
    }

    private fun publish() {
        var now = 0
        for (bits in fingers.values) now = now or bits
        now = now and TouchLayout.GAME_BITS
        if (now == held) return
        if (now and held.inv() != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        held = now
        onKeys(now)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        drawDpad(canvas, l)
        for (b in l.rounds) {
            val down = b.bits != TouchLayout.MENU && held and b.bits != 0
            fill.color = face(down)
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            label(canvas, b.label, b.cx, b.cy, b.r * 0.9f)
        }
        for (p in l.pills) {
            fill.color = face(held and p.bits != 0)
            rect.set(p.left, p.top, p.right, p.bottom)
            val corner = (p.bottom - p.top) / 2
            canvas.drawRoundRect(rect, corner, corner, fill)
            canvas.drawRoundRect(rect, corner, corner, stroke)
            label(canvas, p.label, p.cx, p.cy, if (p.label.length > 1) (p.bottom - p.top) * 0.42f else (p.bottom - p.top) * 0.6f)
        }
    }

    private fun drawDpad(canvas: Canvas, l: TouchLayout) {
        val arm = l.dpadR * 0.36f        // half an arm's width
        val corner = arm * 0.35f
        fill.color = face(false)
        rect.set(l.dpadX - l.dpadR, l.dpadY - arm, l.dpadX + l.dpadR, l.dpadY + arm)
        canvas.drawRoundRect(rect, corner, corner, fill)
        canvas.drawRoundRect(rect, corner, corner, stroke)
        rect.set(l.dpadX - arm, l.dpadY - l.dpadR, l.dpadX + arm, l.dpadY + l.dpadR)
        canvas.drawRoundRect(rect, corner, corner, fill)
        canvas.drawRoundRect(rect, corner, corner, stroke)
        // Pressed arms light up.
        fill.color = face(true)
        if (held and GbaKeys.LEFT != 0) { rect.set(l.dpadX - l.dpadR, l.dpadY - arm, l.dpadX - arm, l.dpadY + arm); canvas.drawRoundRect(rect, corner, corner, fill) }
        if (held and GbaKeys.RIGHT != 0) { rect.set(l.dpadX + arm, l.dpadY - arm, l.dpadX + l.dpadR, l.dpadY + arm); canvas.drawRoundRect(rect, corner, corner, fill) }
        if (held and GbaKeys.UP != 0) { rect.set(l.dpadX - arm, l.dpadY - l.dpadR, l.dpadX + arm, l.dpadY - arm); canvas.drawRoundRect(rect, corner, corner, fill) }
        if (held and GbaKeys.DOWN != 0) { rect.set(l.dpadX - arm, l.dpadY + arm, l.dpadX + arm, l.dpadY + l.dpadR); canvas.drawRoundRect(rect, corner, corner, fill) }
    }

    private fun label(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float) {
        text.textSize = size
        canvas.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    private fun face(down: Boolean) = if (down) withAlpha(Pixel.YELLOW, 170) else withAlpha(Pixel.INK, 110)

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
