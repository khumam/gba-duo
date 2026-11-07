package com.khumam.gbaduo.companion

/**
 * Where the companion screen goes, given the display the game runs on: the other
 * screen (on the Thor the game can be opened on either one), or, with no other
 * screen or when Settings asks for it, over the game itself, shown by a hotkey.
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

        /** Over the game on its own screen, hidden until the player calls it up: one-screen devices. */
        data object Overlay : Target()
    }

    /**
     * [presentationDisplayIds] are the displays Android offers for presentations
     * (`DisplayManager.DISPLAY_CATEGORY_PRESENTATION`: secondary displays, possibly
     * including the game's own). [preferOverlay] (Settings) puts it over the game even when there is another screen.
     */
    fun choose(gameDisplayId: Int, presentationDisplayIds: List<Int>, mainDisplayId: Int = MAIN_DISPLAY, preferOverlay: Boolean = false): Target {
        if (preferOverlay) return Target.Overlay
        presentationDisplayIds.firstOrNull { it != gameDisplayId && it != mainDisplayId }?.let { return Target.Presentation(it) }
        if (gameDisplayId != mainDisplayId) return Target.Activity(mainDisplayId)
        return Target.Overlay
    }
}
