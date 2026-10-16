package com.khumam.gbaduo.companion

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import com.khumam.gbaduo.companion.android.ComposeHostOwner
import com.khumam.gbaduo.companion.android.companionView
import com.khumam.gbaduo.companion.android.setUpForCompanion

/**
 * Hosts the companion screen ([controller]) on a secondary display, while the game
 * runs on the main one.
 *
 * Its window is not focusable, so touching this screen never pulls controller
 * focus away from the game (touch input still works). If a key event reaches it
 * anyway, it's handed to [onKey] so the game still gets it.
 */
class CompanionPresentation(
    context: Context,
    display: Display,
    private val controller: CompanionController,
    private val onKey: (KeyEvent) -> Boolean,
) : Presentation(context, display) {

    private val owner = ComposeHostOwner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setUpForCompanion()
        setContentView(companionView(context, owner, controller))
    }

    override fun onStop() {
        owner.destroy()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
}
