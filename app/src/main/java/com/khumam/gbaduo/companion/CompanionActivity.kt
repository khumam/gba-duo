package com.khumam.gbaduo.companion

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import com.khumam.gbaduo.companion.android.ComposeHostOwner
import com.khumam.gbaduo.companion.android.companionView
import com.khumam.gbaduo.companion.android.setUpForCompanion

/**
 * Hosts the companion screen on the main display while the game runs on a
 * secondary one. A `Presentation` can't be shown on the main display, so this is
 * an activity in its own task there, started and finished by the game through
 * [show] and [close]. It never runs on its own: if Android brings it back after
 * the game has ended, it finishes right away.
 *
 * Like [CompanionPresentation], its window is not focusable, so touching it never
 * pulls controller focus away from the game, and stray key events go to the game.
 */
class CompanionActivity : Activity() {

    /** One game's link to its companion activity. Main thread only. */
    class Session internal constructor(
        internal val controller: CompanionController,
        internal val onKey: (KeyEvent) -> Boolean,
        internal val onShown: () -> Unit,
    ) {
        internal var activity: CompanionActivity? = null
        internal var shown = false
    }

    private val owner = ComposeHostOwner()
    private var session: Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = current
        if (s == null) {
            finishAndRemoveTask()
            return
        }
        session = s
        s.activity = this
        window.setUpForCompanion()
        setContentView(companionView(this, owner, s.controller))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new game started while this one was still around: show the new game's companion.
        if (current !== session) recreate()
    }

    override fun onResume() {
        super.onResume()
        val s = session ?: return
        if (!s.shown) {
            s.shown = true
            s.onShown()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        session?.onKey?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onDestroy() {
        owner.destroy()
        session?.let { if (it.activity === this) it.activity = null }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CompanionActivity"

        private var current: Session? = null

        /**
         * Shows [controller] on display [displayId] (the main display). [onShown] runs once
         * it's on screen. Null if Android refused to start it there.
         */
        fun show(
            from: Activity,
            displayId: Int,
            controller: CompanionController,
            onKey: (KeyEvent) -> Boolean,
            onShown: () -> Unit,
        ): Session? {
            current?.let(::close)
            val session = Session(controller, onKey, onShown)
            current = session
            val intent = Intent(from, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            return try {
                from.startActivity(intent, options.toBundle())
                session
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not show the companion on display $displayId", e)
                current = null
                null
            }
        }

        /** Ends [session]: finishes its activity, or stops it from showing if it's still starting. */
        fun close(session: Session) {
            if (current === session) current = null
            session.activity?.finishAndRemoveTask()
            session.activity = null
        }
    }
}
