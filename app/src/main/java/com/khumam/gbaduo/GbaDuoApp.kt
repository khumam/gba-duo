package com.khumam.gbaduo

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.khumam.gbaduo.companion.CompanionActivity
import com.khumam.gbaduo.companion.ui.CompanionShelfActivity
import com.khumam.gbaduo.companion.ui.FocusBackActivity

/**
 * Closes the second screen's clock when the app leaves the foreground altogether (the Home or menu button): that
 * screen is a separate task, so nothing else would take it away. Moving between the app's own screens (library,
 * settings, a game) never gets the count to zero.
 */
class GbaDuoApp : Application() {

    private var started = 0
    private val handler = Handler(Looper.getMainLooper())
    private val closeShelf = Runnable {
        if (started == 0) sendBroadcast(Intent(CompanionShelfActivity.ACTION_CLOSE).setPackage(packageName))
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (!counts(activity)) return
                started++
                handler.removeCallbacks(closeShelf)
            }

            override fun onActivityStopped(activity: Activity) {
                if (!counts(activity)) return
                started = (started - 1).coerceAtLeast(0)
                // A short wait: a screen change stops one activity just after starting the next, and a rotation
                // restarts the same one.
                if (started == 0) handler.postDelayed(closeShelf, CLOSE_DELAY_MS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** The second-screen helpers are not "the app being open". */
    private fun counts(activity: Activity) =
        activity !is CompanionShelfActivity && activity !is FocusBackActivity && activity !is CompanionActivity

    private companion object {
        const val CLOSE_DELAY_MS = 600L
    }
}
