package com.khumam.gbaduo.library.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.khumam.gbaduo.library.GameTitle
import com.khumam.gbaduo.library.RomEntry
import kotlin.math.roundToInt

/**
 * The split view's list: one row per game, its name and format (GBA / GBC / GB), no cartridge.
 * The selection lives in the carousel; the activity mirrors it here with [select].
 */
class GameList(
    private val recycler: RecyclerView,
    private val onTap: (Int) -> Unit,
) {
    private val context = recycler.context
    private val layoutManager = LinearLayoutManager(context)
    private val adapter = Adapter()
    private val rowHeight = Pixel.dp(context, 44f).roundToInt()

    private var items: List<RomEntry> = emptyList()
    private var selected = -1

    init {
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.isFocusable = false
        recycler.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    fun submit(entries: List<RomEntry>) {
        items = entries
        selected = -1
        adapter.notifyDataSetChanged()
    }

    fun select(position: Int) {
        val old = selected
        selected = position
        if (old in items.indices) adapter.notifyItemChanged(old)
        if (position !in items.indices) return
        adapter.notifyItemChanged(position)
        // Only scroll when the row has left the view, so stepping doesn't jolt the list.
        val first = layoutManager.findFirstCompletelyVisibleItemPosition()
        val last = layoutManager.findLastCompletelyVisibleItemPosition()
        if (position < first || position > last) {
            layoutManager.scrollToPositionWithOffset(position, ((recycler.height - rowHeight) / 2).coerceAtLeast(0))
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val row: LinearLayout, val name: TextView, val format: TextView) : RecyclerView.ViewHolder(row)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val dp = { v: Float -> Pixel.dp(context, v).roundToInt() }
            val name = TextView(context).apply {
                typeface = Fonts.body(context, 600)
                textSize = 15f
                setTextColor(Pixel.INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val format = TextView(context).apply {
                typeface = Fonts.display(context)
                textSize = 11f
                letterSpacing = 0.1f
                setTextColor(Pixel.YELLOW)
                background = ColorDrawable(Pixel.INK)
                setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(10f) }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), 0, dp(14f), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
                isClickable = true
                addView(name)
                addView(format)
            }
            val holder = Holder(row, name, format)
            row.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onTap(position)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.name.text = GameTitle.display(entry.fileName, entry.title)
            holder.format.text = entry.platform.label
            holder.row.background = if (position == selected) ColorDrawable(Pixel.YELLOW_SOFT) else ColorDrawable(Color.TRANSPARENT)
        }
    }
}
