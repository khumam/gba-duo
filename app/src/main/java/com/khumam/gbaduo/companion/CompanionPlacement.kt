package com.khumam.gbaduo.companion

/**
 * Where the companion screen goes, given the display the game runs on: always
 * the other screen. On the Thor the game can be opened on either screen.
 */
object CompanionPlacement {

    /** `Display.DEFAULT_DISPLAY`: the main (top) screen. */
    const val MAIN_DISPLAY = 0

    sealed class Target {
        /** A `Presentation` on this secondary display (the game is on the main one). */
        data class Presentation(val displayId: Int) : Target()

        /**
         * [CompanionActivity] on the main display: the game is on a secondary one, and
         * Android only allows a `Presentation` on secondary displays.
         */
        data class Activity(val displayId: Int) : Target()

        /** No other screen: no companion. */
        data object None : Target()
    }

    /**
     * [presentationDisplayIds] are the displays Android offers for presentations
     * (`DisplayManager.DISPLAY_CATEGORY_PRESENTATION`: secondary displays, possibly
     * including the game's own).
     */
    fun choose(gameDisplayId: Int, presentationDisplayIds: List<Int>, mainDisplayId: Int = MAIN_DISPLAY): Target {
        presentationDisplayIds.firstOrNull { it != gameDisplayId && it != mainDisplayId }?.let { return Target.Presentation(it) }
        if (gameDisplayId != mainDisplayId) return Target.Activity(mainDisplayId)
        return Target.None
    }
}
