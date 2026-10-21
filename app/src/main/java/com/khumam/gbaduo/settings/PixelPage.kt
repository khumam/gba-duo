package com.khumam.gbaduo.settings

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.khumam.gbaduo.BuildConfig
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.ui.DialogBoxDrawable
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.OutlineText
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable
import com.khumam.gbaduo.library.ui.PixelButton

/**
 * The settings pages' shared look: the GBA Duo design system's dark surface (ink-indigo
 * with a faint grid), a yellow outlined title, lavender pixel cards with hard shadows,
 * and a HUD strip of control hints. Every page works with the controller: the D-pad moves
 * focus (the focused card turns yellow), A clicks it, B goes back.
 */
abstract class PixelPage : Activity() {

    protected lateinit var content: LinearLayout
        private set

    /** Builds the page frame and returns; add the page's pieces to [content]. */
    protected fun page(title: String, hints: List<Pair<String, String>> = DEFAULT_HINTS) {
        window.setBackgroundDrawable(NightGridDrawable(this))
        window.statusBarColor = Pixel.INK
        window.navigationBarColor = SHADE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0 // light status bar icons on the dark page

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(14), dp(28), dp(6))
        }
        header.addView(button(getString(R.string.back), Pixel.LAVENDER) { finish() }.apply {
            minHeight = dp(44)
            textSize = 13f
        })
        header.addView(OutlineText(this).apply {
            text = title.uppercase()
            setTextSizeSp(26f)
            fillColor = Pixel.YELLOW
            strokeColor = SHADE
            shadowColor = SHADE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(20) }
        })
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(14), dp(34), dp(28))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(hintsBar(hints))
        setContentView(root)
    }

    private fun hintsBar(hints: List<Pair<String, String>>): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ColorDrawable(SHADE)
            setPadding(dp(28), 0, dp(28), 0)
        }
        val sb = SpannableStringBuilder()
        for ((key, label) in hints) {
            if (sb.isNotEmpty()) sb.append("     ")
            sb.span(key.uppercase(), Pixel.YELLOW).append(' ').span(label.uppercase(), Pixel.ON_INK_SOFT)
        }
        bar.addView(TextView(this).apply {
            text = sb
            typeface = Fonts.display(this@PixelPage)
            textSize = 11f
            letterSpacing = 0.06f
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "V${BuildConfig.VERSION_NAME}"
            typeface = Fonts.display(this@PixelPage, bold = true)
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(Pixel.YELLOW)
        })
        return FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        }
    }

    // ---- controller -----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> { currentFocus?.performClick(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Where D-pad focus starts on this page. Opened by touch, Android only shows focus after
     * the first D-pad press, which then lands here instead of on the Back button.
     */
    protected fun focusFirst(view: View) {
        view.isFocusedByDefault = true
        view.post { view.requestFocus() }
    }

    // ---- pieces -----------------------------------------------------------------------

    /** A menu card: coloured icon tile, Silkscreen title, Plex summary, and a pointer. */
    protected fun menuCard(icon: Int, tile: Int, title: String, summary: String, onClick: () -> Unit): Pair<View, TextView> {
        val box = PixelBoxDrawable(this, Pixel.LAVENDER, shadowDp = 6f, shadowColor = SHADE, focusedFill = Pixel.YELLOW)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box
            setPadding(dp(16), dp(14), dp(18) + box.shadow.toInt(), dp(14) + box.shadow.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has -> v.translationX = if (has) -dp(2).toFloat() else 0f; v.translationY = v.translationX }
        }
        card.addView(FrameLayout(this).apply {
            background = PixelBoxDrawable(this@PixelPage, tile, shadowDp = 0f, borderDp = 3f)
            addView(ImageView(this@PixelPage).apply {
                setImageResource(icon)
                setColorFilter(Pixel.INK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = title.uppercase()
            typeface = Fonts.display(this@PixelPage, bold = true)
            textSize = 16f
            letterSpacing = 0.04f
            setTextColor(Pixel.INK)
        })
        val summaryView = TextView(this).apply {
            text = summary
            typeface = Fonts.body(this@PixelPage, 500)
            textSize = 13f
            setTextColor(Pixel.INK_SOFT)
        }
        column.addView(summaryView)
        card.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        card.addView(TextView(this).apply {
            text = "▶"
            textSize = 14f
            setTextColor(Pixel.INK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        content.addView(card, spaced(16))
        return card to summaryView
    }

    /** A press-in pixel button that also takes D-pad focus (shown with a white face). */
    protected fun button(label: String, fill: Int, onClick: () -> Unit): PixelButton =
        PixelButton(this).apply {
            text = label
            style(fill)
            box.shadowColor = SHADE
            // DESIGN.md's focus ring: pink, clear on every button colour.
            box.focusedBorder = Pixel.PINK
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** A Silkscreen label over a pixel input field (lavender face, ink outline). */
    protected fun field(label: String, inputType: Int, password: Boolean = false): EditText {
        content.addView(label(label), spaced(12))
        val input = EditText(this).apply {
            // Single-line first: setSingleLine() swaps the transformation method, which would
            // undo the password dots if it ran after the input type.
            isSingleLine = true
            this.inputType = inputType
            if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            typeface = Fonts.body(this@PixelPage, 500)
            textSize = 15f
            setTextColor(Pixel.INK)
            setHintTextColor(Pixel.INK_SOFT)
            background = PixelBoxDrawable(this@PixelPage, Pixel.LAVENDER, shadowDp = 4f, shadowColor = SHADE, focusedFill = Pixel.WHITE_SOFT)
            setPadding(dp(12), dp(10), dp(12) + dp(4), dp(10) + dp(4))
        }
        content.addView(input, spaced(6))
        return input
    }

    protected fun label(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        typeface = Fonts.display(this@PixelPage)
        textSize = 11f
        letterSpacing = 0.1f
        setTextColor(Pixel.ON_INK_SOFT)
    }

    /** Small reading text on the dark page. */
    protected fun note(text: CharSequence, top: Int = 12) = TextView(this).apply {
        this.text = text
        typeface = Fonts.body(this@PixelPage)
        textSize = 13f
        setLineSpacing(0f, 1.35f)
        setTextColor(Pixel.ON_INK_SOFT)
    }.also { content.addView(it, spaced(top)) }

    /** HUD readout: ink cells, Silkscreen label in lavender-grey, value in white or yellow. */
    protected fun hud(cells: List<Pair<String, String>>): TextView {
        val sb = SpannableStringBuilder()
        for ((label, value) in cells) {
            if (sb.isNotEmpty()) sb.append("    ")
            sb.span(label.uppercase(), Pixel.ON_INK_SOFT).append(' ').span(value, Pixel.YELLOW)
        }
        return TextView(this).apply {
            text = sb
            typeface = Fonts.display(this@PixelPage)
            textSize = 13f
            letterSpacing = 0.06f
            background = PixelBoxDrawable(this@PixelPage, SHADE, shadowDp = 0f, borderDp = 2f, border = Pixel.PURPLE_DARK)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
    }

    /** DESIGN.md's dialog box, with its yellow tab, holding [build]'s views. */
    protected fun dialogBox(tab: String, build: LinearLayout.() -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DialogBoxDrawable(this@PixelPage)
            setPadding(dp(30), dp(30), dp(36), dp(32))
            clipChildren = false
            clipToPadding = false
        }
        val tabView = TextView(this).apply {
            text = tab.uppercase()
            typeface = Fonts.display(this@PixelPage, bold = true)
            textSize = 14f
            letterSpacing = 0.08f
            setTextColor(Pixel.INK)
            background = PixelBoxDrawable(this@PixelPage, Pixel.YELLOW, shadowDp = 3f, borderDp = 3f)
            setPadding(dp(12), dp(6), dp(12) + dp(3), dp(6) + dp(3))
        }
        val frame = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(18) })
        frame.addView(tabView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(24) })
        box.build()
        content.addView(frame, spaced(8))
        return box
    }

    /** Body text inside a lavender box (ink on lavender). */
    protected fun boxText(text: CharSequence, size: Float = 14f, top: Int = 0) = TextView(this).apply {
        this.text = text
        typeface = Fonts.body(this@PixelPage, 500)
        textSize = size
        setLineSpacing(0f, 1.4f)
        setTextColor(Pixel.INK)
        if (top > 0) setPadding(0, dp(top), 0, 0)
    }

    protected fun spaced(top: Int, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    protected fun dp(v: Int) = Pixel.dp(this, v.toFloat()).toInt()

    private fun SpannableStringBuilder.span(text: String, color: Int): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    companion object {
        /** Darker than the ink page: hard shadows and the hints bar. */
        const val SHADE = 0xFF120C26.toInt()

        val DEFAULT_HINTS = listOf("▲ ▼" to "Choose", "A" to "Open", "B" to "Back")
    }
}

/** The dark page: ink-indigo with the same 24dp grid as the library, much fainter. */
class NightGridDrawable(context: Context) : Drawable() {
    private val step = Pixel.dp(context, 24f)
    private val line = Paint().apply { color = 0x14B3ABD3; strokeWidth = Pixel.dp(context, 1f) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(Pixel.INK)
        var x = b.left.toFloat()
        while (x <= b.right) { canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), line); x += step }
        var y = b.top.toFloat()
        while (y <= b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, line); y += step }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
