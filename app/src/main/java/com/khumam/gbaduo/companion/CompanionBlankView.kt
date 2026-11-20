package com.khumam.gbaduo.companion

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.library.artwork.Artwork
import com.khumam.gbaduo.library.artwork.ArtworkImages
import com.khumam.gbaduo.library.ui.CartridgeView
import java.util.concurrent.Executors

/**
 * [CompanionMode.BLANK_ART]: a black screen with the game's cartridge, faded, in the middle.
 * Uses the cover from the artwork cache when the game has one (the same cartridge the library
 * draws, otherwise its title). Takes no touches.
 */
class CompanionBlankView(
    context: Context,
    title: String,
    gameCode: String,
    platform: Platform,
    crc32: Long,
) : FrameLayout(context) {

    private val cart = CartridgeView(context).also {
        it.title = title
        it.gameCode = gameCode
        it.platform = platform
        it.alpha = CART_ALPHA
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
        isFocusable = false
        // Same shape the library gives a cartridge; a bit under half the screen's height.
        val side = (context.resources.displayMetrics.heightPixels * CART_SCREEN_FRACTION).toInt()
        addView(cart, LayoutParams(side, side, Gravity.CENTER))
        loadArt(context.applicationContext, platform, crc32)
    }

    // Swallow touches: nothing here reacts, and nothing reaches whatever is under it.
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

    private fun loadArt(app: Context, platform: Platform, crc32: Long) {
        val density = app.resources.displayMetrics.density
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val file = Artwork.store(app).file(platform.id, crc32)
            val bitmap = file?.let { ArtworkImages.decode(it, (ART_W_DP * density).toInt(), (ART_H_DP * density).toInt()) }
            if (bitmap != null) Handler(Looper.getMainLooper()).post { cart.art = bitmap }
            executor.shutdown()
        }
    }

    private companion object {
        const val CART_ALPHA = 0.35f
        const val CART_SCREEN_FRACTION = 0.6f
        const val ART_W_DP = 180f
        const val ART_H_DP = 200f
    }
}
