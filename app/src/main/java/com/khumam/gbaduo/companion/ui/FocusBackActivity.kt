package com.khumam.gbaduo.companion.ui

import android.app.Activity

/**
 * Invisible and instant: launching an activity on a screen is what makes that screen the one the controller talks to,
 * so launching this on the main screen gives the controller back after the shelf was opened on the second.
 */
class FocusBackActivity : Activity() {
    override fun onResume() {
        super.onResume()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
