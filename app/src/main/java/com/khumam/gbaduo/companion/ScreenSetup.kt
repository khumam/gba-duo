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

    /** The display id for the second screen, or null on one-screen devices. */
    fun secondaryDisplayId(presentationDisplayIds: List<Int>, mainDisplayId: Int = 0): Int? =
        presentationDisplayIds.firstOrNull { it != mainDisplayId }

    /** The second screen's display id as Android lists it now, or null when there is none. */
    fun secondaryDisplayId(context: Context): Int? = secondaryDisplayId(
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.map { it.displayId }.orEmpty(),
    )

    /** [presentationDisplayIds]: `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`, the screens besides the built-in one. */
    fun isDual(presentationDisplayIds: List<Int>): Boolean = presentationDisplayIds.isNotEmpty()
}
