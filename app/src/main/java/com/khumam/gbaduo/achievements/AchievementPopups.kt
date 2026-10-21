package com.khumam.gbaduo.achievements

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable

/**
 * RetroAchievements popups over the game, top-right, one at a time: unlocks, the
 * "X of Y unlocked" summary when a game starts, and connection notes. Same look as the
 * library: ink panel, yellow rim, hard shadow, Silkscreen label, Plex Mono text.
 */
class AchievementPopups(private val container: FrameLayout) : RetroAchievements.GameListener {

    private val context: Context = container.context
    private val queue = ArrayDeque<Popup>()
    private var showing: View? = null
    private val easeOut = PathInterpolator(0.25f, 1f, 0.5f, 1f)
    private val motion = Pixel.motionEnabled(context)

    private data class Popup(
        val label: String,
        val title: String,
        val detail: String?,
        val points: Int,
        val imageUrl: String?,
        val millis: Long,
    )

    fun attach() = RetroAchievements.addGameListener(this)

    fun detach() {
        RetroAchievements.removeGameListener(this)
        queue.clear()
        showing?.let(container::removeView)
        showing = null
    }

    override fun onGameLoaded(title: String?, total: Int, unlocked: Int, imageUrl: String?, error: String?) {
        val label = context.getString(R.string.ra_title)
        when {
            error != null -> enqueue(Popup(label, context.getString(R.string.ra_game_error, error), null, 0, null, 3500))
            total == 0 -> enqueue(Popup(label, title ?: "", context.getString(R.string.ra_no_achievements), 0, imageUrl, 3500))
            else -> enqueue(Popup(label, title ?: "", context.getString(R.string.ra_game_summary, unlocked, total), 0, imageUrl, 3500))
        }
    }

    override fun onEvent(type: Int, title: String?, description: String?, points: Int, badgeUrl: String?) {
        when (type) {
            RetroAchievements.EVENT_ACHIEVEMENT ->
                enqueue(Popup(context.getString(R.string.ra_unlocked), title ?: "", description, points, badgeUrl, 5000))
            RetroAchievements.EVENT_GAME_COMPLETED ->
                enqueue(Popup(context.getString(R.string.ra_mastered), title ?: "", null, 0, null, 5000))
            RetroAchievements.EVENT_DISCONNECTED ->
                enqueue(Popup(context.getString(R.string.ra_title), context.getString(R.string.ra_disconnected), null, 0, null, 3500))
            RetroAchievements.EVENT_RECONNECTED ->
                enqueue(Popup(context.getString(R.string.ra_title), context.getString(R.string.ra_reconnected), null, 0, null, 2500))
            RetroAchievements.EVENT_SERVER_ERROR ->
                enqueue(Popup(context.getString(R.string.ra_title), description ?: title ?: "", null, 0, null, 3500))
        }
    }

    private fun enqueue(popup: Popup) {
        queue.addLast(popup)
        if (showing == null) showNext()
    }

    private fun showNext() {
        val popup = queue.removeFirstOrNull() ?: return
        val view = build(popup)
        showing = view
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, dp(16f), dp(16f), 0) })

        val duration = if (motion) 220L else 0L
        view.translationY = if (motion) -dp(24f).toFloat() else 0f
        view.alpha = if (motion) 0f else 1f
        view.animate().translationY(0f).alpha(1f).setDuration(duration).setInterpolator(easeOut).start()
        view.postDelayed({
            view.animate().alpha(0f).setDuration(duration).withEndAction {
                container.removeView(view)
                if (showing === view) showing = null
                showNext()
            }.start()
        }, popup.millis)
    }

    private fun build(popup: Popup): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val box = PixelBoxDrawable(context, Pixel.INK, shadowDp = 4f, borderDp = 3f, border = Pixel.YELLOW)
            background = box
            setPadding(dp(12f), dp(10f), dp(16f) + box.shadow.toInt(), dp(10f) + box.shadow.toInt())
            minimumWidth = dp(320f)
            elevation = 0f
        }
        if (popup.imageUrl != null) {
            val badge = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f)).apply { marginEnd = dp(12f) }
                background = PixelBoxDrawable(context, Pixel.PURPLE_DARK, shadowDp = 0f, borderDp = 2f, border = Pixel.ON_INK_SOFT)
                setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            panel.addView(badge)
            RetroAchievements.fetchImage(popup.imageUrl) { bitmap: Bitmap -> badge.setImageBitmap(bitmap) }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = popup.label.uppercase()
            typeface = Fonts.display(context, bold = true)
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(Pixel.YELLOW)
        })
        column.addView(TextView(context).apply {
            text = popup.title
            typeface = Fonts.body(context, 600)
            textSize = 15f
            setTextColor(Pixel.ON_INK)
            maxWidth = dp(420f)
            maxLines = 2
        })
        if (!popup.detail.isNullOrBlank()) {
            column.addView(TextView(context).apply {
                text = popup.detail
                typeface = Fonts.body(context)
                textSize = 12f
                setTextColor(Pixel.ON_INK_SOFT)
                maxWidth = dp(420f)
                maxLines = 3
            })
        }
        panel.addView(column)
        if (popup.points > 0) {
            panel.addView(TextView(context).apply {
                text = context.resources.getQuantityString(R.plurals.ra_points, popup.points, popup.points)
                typeface = Fonts.display(context, bold = true)
                textSize = 11f
                setTextColor(Pixel.INK)
                background = PixelBoxDrawable(context, Pixel.YELLOW, shadowDp = 0f, borderDp = 0f)
                setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(14f) }
            })
        }
        return panel
    }

    private fun dp(v: Float) = Pixel.dp(context, v).toInt()
}
