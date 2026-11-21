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
 * rows that light up yellow with a pink outline under D-pad focus. Solid, and shown and hidden
 * without animation. A confirms, B and Back cancel.
 */
class PixelMenuDialog(
    context: Context,
    private val title: String,
    private val items: List<Pair<String, () -> Unit>>,
    /** The row that has the cursor when the menu opens. */
    private val focusIndex: Int = 0,
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
            setWindowAnimations(0) // no fade or zoom: it is there at once, and gone at once
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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
        items.forEachIndexed { index, (label, action) ->
            val row = row(label) { dismiss(); action() }
            if (first == null || index == focusIndex) first = row
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
        // From touch: the screen is usually in touch mode (the last input was a tap, or none yet), where a plain
        // requestFocus is ignored and the first D-pad press would light up the top row instead of this one.
        first?.post { first?.requestFocusFromTouch() }
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
        // Solid: nothing behind shows through the panel or its rows.
        const val PANEL_FILL = Pixel.INK
        const val SHADOW = 0x99000000.toInt()
        const val ROW_FILL = Pixel.PURPLE_DARK
        const val ROW_BORDER = Pixel.PURPLE
    }
}
