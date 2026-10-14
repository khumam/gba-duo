package com.khumam.gbaduo.companion.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/** What the companion UI needs from the platform it runs on (Android in the app, desktop in tests). */
interface CompanionPlatform {
    /** The first family in a CSS `font-family` list that resolves (pack fonts, then generic families). */
    fun fontFamily(families: List<String>): FontFamily

    /** An image from a CSS `url()`, resolved against [baseDir] (the pack folder if null) and kept inside the pack. */
    fun cssImage(url: String, baseDir: File?): ImageBitmap?

    /** Makes [paint] draw blurred by [radius] px, for `box-shadow`. */
    fun setBlur(paint: Paint, radius: Float)
}
