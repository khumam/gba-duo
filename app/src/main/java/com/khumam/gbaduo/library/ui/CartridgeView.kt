package com.khumam.gbaduo.library.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.khumam.gbaduo.library.Platform
import kotlin.math.min

/**
 * A game drawn as its cartridge, since GBA/GB ROMs carry no cover art: the platform's
 * shell shape and colour, ink outlines, a hard shadow, and a label sticker with the title.
 *
 * - GBA: a wide shell with a stepped top-left corner and grip ridges.
 * - GB / GBC: a tall shell with the cut top-right corner and the arrow embossed below.
 *
 * [selected] lifts it: a yellow ring outside the outline and a deeper shadow.
 */
class CartridgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var title: String = ""
        set(value) { field = value; labelLayout = null; invalidate() }
    var gameCode: String = ""
        set(value) { field = value; invalidate() }
    var platform: Platform = Platform.GBA
        set(value) { field = value; labelLayout = null; invalidate() }
    /** Cover art for the label; null draws the title instead. */
    var art: Bitmap? = null
        set(value) { if (field !== value) { field = value; invalidate() } }
    var hasPack = false
        set(value) { field = value; invalidate() }
    var selectedCart = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private fun dp(v: Float) = Pixel.dp(context, v)

    private val outline = dp(3f)
    private val ring = dp(4f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path()
    private val bodyBounds = RectF()
    private val label = RectF()
    private val tmp = RectF()

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display(context, bold = true)
        color = Pixel.INK
    }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display(context)
    }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.body(context, 600)
        color = Pixel.INK_SOFT
    }
    private var labelLayout: StaticLayout? = null
    private var labelLayoutWidth = 0

    private val shellColor get() = when (platform) {
        Platform.GBA -> Pixel.PURPLE
        Platform.GBC -> Pixel.YELLOW
        Platform.GB -> Pixel.SCREEN
    }

    /** The label's header band: one of the brand accents, picked from the title so it's stable. */
    private val accent get() = ACCENTS[(title.hashCode() and 0x7fffffff) % ACCENTS.size]

    private val platformName get() = when (platform) {
        Platform.GBA -> "GAME BOY ADVANCE"
        Platform.GBC -> "GAME BOY COLOR"
        Platform.GB -> "GAME BOY"
    }

    override fun onDraw(canvas: Canvas) {
        val shadow = if (selectedCart) dp(9f) else dp(6f)
        val pad = ring + dp(2f)
        val availW = width - pad * 2 - shadow
        val availH = height - pad * 2 - shadow
        // Every shell is portrait, so a cover (usually 3:4 or square) fills the label.
        val aspect = SHELL_ASPECT
        var w = availW
        var h = w / aspect
        if (h > availH) { h = availH; w = h * aspect }
        val left = pad + (availW - w) / 2
        val top = pad + (availH - h) / 2
        bodyBounds.set(left, top, left + w, top + h)
        buildBody(bodyBounds)

        // Hard shadow, then the yellow ring when selected, then the shell.
        canvas.save()
        canvas.translate(shadow, shadow)
        paint.style = Paint.Style.FILL
        paint.color = Pixel.INK
        canvas.drawPath(body, paint)
        canvas.restore()

        if (selectedCart) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.MITER
            paint.strokeWidth = outline + ring * 2
            paint.color = Pixel.YELLOW
            canvas.drawPath(body, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = shellColor
        canvas.drawPath(body, paint)
        drawHighlight(canvas)
        drawRidges(canvas)

        // Label sticker.
        layoutLabel(bodyBounds)
        drawLabel(canvas)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outline
        paint.strokeJoin = Paint.Join.MITER
        paint.color = Pixel.INK
        canvas.drawPath(body, paint)

        if (hasPack) drawPackSticker(canvas)
    }

    private fun buildBody(b: RectF) {
        body.reset()
        val step = min(b.width(), b.height()) * 0.09f
        if (platform == Platform.GBA) {
            // Stepped top-left corner, two pixel steps.
            body.moveTo(b.left + step * 2, b.top)
            body.lineTo(b.right, b.top)
            body.lineTo(b.right, b.bottom)
            body.lineTo(b.left, b.bottom)
            body.lineTo(b.left, b.top + step * 2)
            body.lineTo(b.left + step, b.top + step * 2)
            body.lineTo(b.left + step, b.top + step)
            body.lineTo(b.left + step * 2, b.top + step)
            body.close()
        } else {
            // The Game Boy cart's cut top-right corner.
            val cut = step * 1.6f
            body.moveTo(b.left, b.top)
            body.lineTo(b.right - cut, b.top)
            body.lineTo(b.right, b.top + cut)
            body.lineTo(b.right, b.bottom)
            body.lineTo(b.left, b.bottom)
            body.close()
        }
    }

    /** One diagonal highlight band across the shell, like the device illustration. */
    private fun drawHighlight(canvas: Canvas) {
        val b = bodyBounds
        canvas.save()
        canvas.clipPath(body)
        val band = Path().apply {
            val x = b.left + b.width() * 0.62f
            val bw = b.width() * 0.09f
            moveTo(x, b.top); lineTo(x + bw, b.top)
            lineTo(x + bw - b.height() * 0.55f, b.bottom); lineTo(x - b.height() * 0.55f, b.bottom); close()
        }
        paint.style = Paint.Style.FILL
        paint.color = shade(shellColor, 0.28f)
        canvas.drawPath(band, paint)
        canvas.restore()
    }

    /** Grip ridges across the top of the shell. */
    private fun drawRidges(canvas: Canvas) {
        val b = bodyBounds
        paint.style = Paint.Style.FILL
        paint.color = shade(shellColor, -0.28f)
        val t = dp(2f)
        for (i in 0 until 3) {
            val y = b.top + b.height() * (0.045f + i * 0.03f)
            canvas.drawRect(b.left + b.width() * 0.16f, y, b.right - b.width() * 0.3f, y + t, paint)
        }
    }

    private fun layoutLabel(b: RectF) {
        label.set(b.left + b.width() * 0.07f, b.top + b.height() * 0.17f,
            b.right - b.width() * 0.07f, b.bottom - b.height() * 0.07f)
    }

    private fun drawLabel(canvas: Canvas) {
        val lw = dp(2f)
        val cover = art
        if (cover != null) {
            fitLabelTo(cover)
            drawArt(canvas, cover)
            drawPlatformTag(canvas)
        } else {
            // Face, header band with the platform name, then the title.
            paint.style = Paint.Style.FILL
            paint.color = Pixel.LAVENDER
            canvas.drawRect(label, paint)
            val bandH = label.height() * 0.14f
            paint.color = accent
            canvas.drawRect(label.left, label.top, label.right, label.top + bandH, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = lw
            paint.color = Pixel.INK
            canvas.drawLine(label.left, label.top + bandH, label.right, label.top + bandH, paint)

            smallPaint.textSize = (bandH * 0.5f).coerceIn(dp(6f), dp(11f))
            smallPaint.color = Pixel.INK
            val name = TextUtils.ellipsize(platformName, smallPaint, label.width() - dp(8f), TextUtils.TruncateAt.END)
            val fm = smallPaint.fontMetrics
            canvas.drawText(name, 0, name.length, label.left + dp(5f),
                label.top + bandH / 2 - (fm.ascent + fm.descent) / 2, smallPaint)
            drawTitle(canvas, bandH)
        }
        // Outline.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lw
        paint.color = Pixel.INK
        canvas.drawRect(label, paint)
    }

    /** A small ink tag in the cover's bottom-left corner naming the platform. */
    private fun drawPlatformTag(canvas: Canvas) {
        smallPaint.textSize = dp(8f)
        smallPaint.color = Pixel.CREAM
        val text = platform.label
        val padX = dp(5f)
        val w = smallPaint.measureText(text) + padX * 2
        val h = dp(14f)
        paint.style = Paint.Style.FILL
        paint.color = Pixel.INK
        val top = label.bottom - h
        canvas.drawRect(label.left, top, label.left + w, label.bottom, paint)
        val fm = smallPaint.fontMetrics
        canvas.drawText(text, label.left + padX, top + h / 2 - (fm.ascent + fm.descent) / 2, smallPaint)
    }

    /** Shrinks [label] to the cover's own proportions, centred in the face, so nothing is cropped. */
    private fun fitLabelTo(bitmap: Bitmap) {
        val scale = min(label.width() / bitmap.width, label.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        label.set(label.centerX() - w / 2, label.centerY() - h / 2, label.centerX() + w / 2, label.centerY() + h / 2)
    }

    /** The cover, drawn whole into the (already fitted) label. */
    private fun drawArt(canvas: Canvas, bitmap: Bitmap) {
        paint.style = Paint.Style.FILL
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null, label, paint)
        paint.isFilterBitmap = false
    }

    /** Title, up to four lines, centred in the space under the band. */
    private fun drawTitle(canvas: Canvas, bandH: Float) {
        val inner = (label.width() - dp(12f)).toInt().coerceAtLeast(1)
        titlePaint.textSize = (label.height() * 0.105f).coerceIn(dp(8f), dp(15f))
        val layout = labelLayout?.takeIf { labelLayoutWidth == inner }
            ?: StaticLayout.Builder.obtain(title.ifBlank { "?" }, 0, title.ifBlank { "?" }.length, titlePaint, inner)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build().also { labelLayout = it; labelLayoutWidth = inner }
        codePaint.textSize = (label.height() * 0.1f).coerceIn(dp(7f), dp(11f))
        val codeH = if (gameCode.isNotBlank()) codePaint.textSize * 1.4f else 0f
        val areaTop = label.top + bandH
        val areaH = label.height() - bandH - codeH
        canvas.save()
        canvas.translate(label.left + dp(6f), areaTop + (areaH - layout.height) / 2)
        layout.draw(canvas)
        canvas.restore()
        if (gameCode.isNotBlank()) {
            val cw = codePaint.measureText(gameCode)
            canvas.drawText(gameCode, label.centerX() - cw / 2, label.bottom - codePaint.textSize * 0.45f, codePaint)
        }
    }

    /** A tilted pink sticker: this game has a companion pack installed. */
    private fun drawPackSticker(canvas: Canvas) {
        val s = (min(bodyBounds.width(), bodyBounds.height()) * 0.2f).coerceAtLeast(dp(24f))
        val cx = label.right - s * 0.15f
        val cy = label.top - s * 0.05f
        canvas.save()
        canvas.rotate(10f, cx, cy)
        tmp.set(cx - s * 0.9f, cy - s * 0.38f, cx + s * 0.9f, cy + s * 0.38f)
        paint.style = Paint.Style.FILL
        paint.color = Pixel.INK
        canvas.drawRect(tmp.left + dp(2f), tmp.top + dp(2f), tmp.right + dp(2f), tmp.bottom + dp(2f), paint)
        paint.color = Pixel.PINK
        canvas.drawRect(tmp, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Pixel.INK
        canvas.drawRect(tmp, paint)
        smallPaint.textSize = s * 0.34f
        smallPaint.color = Pixel.INK
        val fm = smallPaint.fontMetrics
        val tw = smallPaint.measureText(PACK)
        canvas.drawText(PACK, cx - tw / 2, cy - (fm.ascent + fm.descent) / 2, smallPaint)
        canvas.restore()
    }

    private companion object {
        /** Width over height of the shell: portrait. */
        const val SHELL_ASPECT = 0.78f

        const val PACK = "PACK"
        val ACCENTS = intArrayOf(Pixel.YELLOW, Pixel.CORAL, Pixel.PINK, Pixel.MINT, Pixel.PURPLE_LIGHT)
    }
}
