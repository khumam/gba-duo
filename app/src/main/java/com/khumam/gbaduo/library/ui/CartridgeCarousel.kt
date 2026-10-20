package com.khumam.gbaduo.library.ui

import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.khumam.gbaduo.library.GameTitle
import com.khumam.gbaduo.library.RomEntry
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The library's cartridge carousel: one game centred and selected, its neighbours smaller
 * and faded to the sides. Driven by the D-pad ([step]) as much as by touch: a tap on a
 * side cartridge centres it, a tap on the centred one plays it ([onPlay]).
 *
 * The selection ([selected]) changes at once on a key press, before the scroll finishes,
 * so holding the D-pad steps quickly and the info below never lags.
 */
class CartridgeCarousel(
    private val recycler: RecyclerView,
    private val onSelected: (Int) -> Unit,
    private val onPlay: (Int) -> Unit,
) {
    private val context = recycler.context
    private val itemWidth = Pixel.dp(context, ITEM_WIDTH_DP).roundToInt()
    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private val adapter = Adapter()
    private val easeOut = PathInterpolator(0.25f, 1f, 0.5f, 1f) // ease-out-quart
    private val stepMs = if (Pixel.motionEnabled(context)) 220 else 0

    var items: List<RomEntry> = emptyList()
        private set
    private var packCodes: Set<String> = emptySet()

    /** The selected (centred) position, or -1 when there are no games. */
    var selected = -1
        private set

    init {
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.isFocusable = false
        recycler.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        LinearSnapHelper().attachToRecyclerView(recycler)
        recycler.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) {
                // Side padding so the first and last cartridges can sit in the centre.
                val side = ((r - l) - itemWidth) / 2
                v.setPadding(side, v.paddingTop, side, v.paddingBottom)
                v.post { jumpTo(selected.coerceAtLeast(0)); applyTransforms() }
            }
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyTransforms()
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // A fling or drag ends wherever the snap lands: that's the selection.
                if (newState == RecyclerView.SCROLL_STATE_IDLE) centredPosition()?.let { setSelected(it) }
            }
        })
    }

    fun submit(entries: List<RomEntry>, packs: Set<String>, keepFileName: String?) {
        items = entries
        packCodes = packs
        adapter.notifyDataSetChanged()
        val target = entries.indexOfFirst { it.fileName == keepFileName }.takeIf { it >= 0 }
            ?: selected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        selected = -1
        if (entries.isEmpty()) { onSelected(-1); return }
        recycler.post { jumpTo(target); setSelected(target); applyTransforms() }
    }

    fun hasPack(entry: RomEntry) = entry.gameCode in packCodes

    /** Moves the selection by [delta] (D-pad, shoulder buttons), clamped to the ends. */
    fun step(delta: Int) {
        if (items.isEmpty()) return
        val target = (selected + delta).coerceIn(0, items.size - 1)
        if (target == selected) return
        scrollTo(target)
    }

    private fun scrollTo(position: Int) {
        setSelected(position)
        val view = layoutManager.findViewByPosition(position)
        if (view == null || stepMs == 0) {
            jumpTo(position)
            recycler.post { applyTransforms() }
            return
        }
        val dx = (view.left + view.width / 2) - recycler.width / 2
        recycler.smoothScrollBy(dx, 0, easeOut, stepMs)
    }

    private fun jumpTo(position: Int) {
        if (position < 0 || position >= items.size) return
        layoutManager.scrollToPositionWithOffset(position, 0)
    }

    private fun setSelected(position: Int) {
        if (position == selected) return
        selected = position
        onSelected(position)
        applyTransforms()
    }

    private fun centredPosition(): Int? {
        val centre = recycler.width / 2
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val d = abs(child.left + child.width / 2 - centre)
            if (d < bestDistance) { bestDistance = d; best = recycler.getChildAdapterPosition(child) }
        }
        return best?.takeIf { it != RecyclerView.NO_POSITION }
    }

    /** Scale and fade by distance from the centre (transform and alpha only, no layout). */
    private fun applyTransforms() {
        val centre = recycler.width / 2f
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val distance = min(abs(child.left + child.width / 2f - centre) / itemWidth, 2f)
            val near = min(distance, 1f)
            val scale = 1f - 0.24f * near
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = 1f - 0.4f * (distance / 2f)
            // Pull the neighbours in a little so the row reads as one shelf.
            child.translationX = (if (child.left + child.width / 2f < centre) 1 else -1) * itemWidth * 0.08f * near
            val position = recycler.getChildAdapterPosition(child)
            (child as? CartridgeView)?.selectedCart = position == selected
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val cart: CartridgeView) : RecyclerView.ViewHolder(cart)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cart = CartridgeView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT)
                isClickable = true
                isFocusable = false
            }
            val holder = Holder(cart)
            cart.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                if (position == selected) onPlay(position) else scrollTo(position)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.cart.apply {
                title = GameTitle.display(entry.fileName, entry.title)
                gameCode = entry.gameCode
                platform = entry.platform
                hasPack = hasPack(entry)
                selectedCart = position == selected
                contentDescription = title
            }
        }
    }

    private companion object {
        const val ITEM_WIDTH_DP = 236f
    }
}
