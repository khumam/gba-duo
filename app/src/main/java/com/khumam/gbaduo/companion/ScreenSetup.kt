package com.khumam.gbaduo.companion

import android.content.Context
import android.hardware.display.DisplayManager

/**
 * Whether this device has a second screen for the companion (the Thor) or only one (most handhelds), checked when the
 * app opens. On one screen the companion is shown over the game, so its quick menu entry and hotkey only exist there.
 */
object ScreenSetup {
    @Volatile private var dual: Boolean? = null

    /** Looks at the displays now and remembers the answer. Called when the app opens. */
    fun detect(context: Context): Boolean {
        val displays = context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.map { it.displayId }.orEmpty()
        return isDual(displays).also { dual = it }
    }

    /** What [detect] found when the app opened (or finds now, if it hasn't run yet). */
    fun isDualScreen(context: Context): Boolean = dual ?: detect(context)

    /** [presentationDisplayIds]: `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`, the screens besides the built-in one. */
    fun isDual(presentationDisplayIds: List<Int>): Boolean = presentationDisplayIds.isNotEmpty()
}
