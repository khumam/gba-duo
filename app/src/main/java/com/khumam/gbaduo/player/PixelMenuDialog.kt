package com.khumam.gbaduo.player

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable

/**
 * The in-game menu in the app's look: an ink panel with a purple outline, a Silkscreen title, and
 * rows that light up yellow with a pink outline under D-pad focus. Translucent, so the paused game
 * shows through. A confirms, B and Back cancel.
 */
class PixelMenuDialog(
    context: Context,
    private val title: String,
    private val items: List<Pair<String, () -> Unit>>,
) : Dialog(context) {

    private fun dp(v: Float) = Pixel.dp(context, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.3f)
            setGravity(Gravity.CENTER)
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Ink at ~85%: the game still reads through it.
            background = PixelBoxDrawable(context, PANEL_FILL, shadowDp = 5f, borderDp = 3f, border = Pixel.PURPLE_LIGHT, shadowColor = SHADOW)
            val edge = dp(16f)
            setPadding(edge, edge - dp(2f), edge + Pixel.dp(context, 5f).toInt(), edge + Pixel.dp(context, 5f).toInt())
        }
        panel.addView(TextView(context).apply {
            text = title.uppercase()
            typeface = Fonts.display(context, bold = true)
            textSize = 15f
            letterSpacing = 0.08f
            setTextColor(Pixel.YELLOW)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })

        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        items.forEach { (label, action) ->
            val row = row(label) { dismiss(); action() }
            if (first == null) first = row
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(rows) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val screen = context.resources.displayMetrics
        setContentView(panel, ViewGroup.LayoutParams(minOf(dp(380f), (screen.widthPixels * 0.9f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT))
        // Rows beyond ~80% of the screen's height scroll.
        window?.setLayout(minOf(dp(380f), (screen.widthPixels * 0.9f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        scroll.post {
            val max = (screen.heightPixels * 0.8f).toInt() - panel.paddingTop - panel.paddingBottom - dp(40f)
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        first?.post { first?.requestFocus() }
    }

    private fun row(label: String, onClick: () -> Unit): TextView {
        val box = PixelBoxDrawable(
            context, ROW_FILL, shadowDp = 0f, borderDp = 2f, border = ROW_BORDER,
            focusedFill = Pixel.YELLOW, focusedBorder = Pixel.PINK,
        )
        return TextView(context).apply {
            text = label
            typeface = Fonts.body(context, 600)
            textSize = 15f
            setTextColor(Pixel.ON_INK)
            gravity = Gravity.CENTER_VERTICAL
            background = box
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // Focused: ink text on the yellow face.
            setOnFocusChangeListener { v, focused -> (v as TextView).setTextColor(if (focused) Pixel.INK else Pixel.ON_INK) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> { currentFocus?.performClick(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> { cancel(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val PANEL_FILL = 0xD9241A45.toInt()   // INK at 85%
        const val SHADOW = 0x99000000.toInt()
        const val ROW_FILL = 0x993D2F6E.toInt()      // PURPLE_DARK at 60%
        const val ROW_BORDER = 0x667B6BB5.toInt()    // PURPLE_LIGHT at 40%
    }
}
