package com.khumam.gbaduo.companion.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.GameMarksStore
import com.khumam.gbaduo.library.PlayStats
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.OutlineText
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable
import com.khumam.gbaduo.settings.NightGridDrawable
import com.khumam.gbaduo.settings.PixelPage
import java.util.Date
import java.util.Locale

/**
 * What the second screen shows while no pack list is open: the time and date, and four tiles about the library
 * (games, total playtime, last played game, battery). Takes no touches. Ticks once a minute while attached.
 */
class CompanionClockView(context: Context) : FrameLayout(context) {

    private val time = OutlineText(context).apply {
        setTextSizeSp(72f)
        fillColor = Pixel.YELLOW
        strokeColor = PixelPage.SHADE
        shadowColor = PixelPage.SHADE
        centered = true
    }
    private val date = TextView(context).apply {
        typeface = Fonts.display(context)
        textSize = 13f
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        setTextColor(Pixel.ON_INK_SOFT)
    }
    private val games = Tile(R.string.clock_games)
    private val playtime = Tile(R.string.clock_playtime)
    private val last = Tile(R.string.clock_last)
    private val battery = Tile(R.string.clock_battery)

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        background = NightGridDrawable(context)
        isClickable = true // nothing here reacts, and nothing reaches the screen under it
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        column.addView(row(games, playtime), rowParams(top = 22))
        column.addView(row(last, battery), rowParams(top = 12))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = dp(28)
            marginEnd = dp(28)
        })
        refresh()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Reads the clock, the stats and the battery again. */
    fun refresh() {
        val now = Date()
        time.text = DateFormat.getTimeFormat(context).format(now)
        date.text = java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).uppercase()
        val stats = GameMarksStore.stats(context)
        games.value.text = stats.games.toString()
        playtime.value.text = PlayStats.formatPlaytime(stats.playtimeMs)
        last.value.text = stats.lastTitle ?: context.getString(R.string.clock_none)
        battery.value.text = batteryText()
    }

    private fun batteryText(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return context.getString(R.string.clock_none)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return context.getString(R.string.clock_none)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = "${level * 100 / scale}%"
        return if (charging) context.getString(R.string.clock_charging, percent) else percent
    }

    private fun row(left: Tile, right: Tile) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        addView(right.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
    }

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(v: Int) = Pixel.dp(context, v.toFloat()).toInt()

    /** A lavender pixel card: a small Silkscreen label over the value. */
    private inner class Tile(label: Int) {
        val value = TextView(context).apply {
            typeface = Fonts.display(context, bold = true)
            textSize = 18f
            setTextColor(Pixel.INK)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val box = PixelBoxDrawable(context, Pixel.LAVENDER, shadowDp = 6f, shadowColor = PixelPage.SHADE)
            background = box
            setPadding(dp(14), dp(10), dp(14) + box.shadow.toInt(), dp(10) + box.shadow.toInt())
            addView(TextView(context).apply {
                text = context.getString(label).uppercase()
                typeface = Fonts.display(context)
                textSize = 10f
                letterSpacing = 0.08f
                setTextColor(Pixel.INK_SOFT)
                isSingleLine = true
            })
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }
}
