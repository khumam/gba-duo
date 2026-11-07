package com.khumam.gbaduo.companion.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.khumam.gbaduo.companion.CompanionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class CompanionOverlayTest {

    /** The game's screen: a plain Activity (not a ComponentActivity), as PlayerActivity is. */
    private fun gameScreen(): Pair<Activity, FrameLayout> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        return activity to root
    }

    private fun overlay(activity: Activity, onDismiss: () -> Unit = {}) =
        CompanionOverlay(activity, CompanionController(activity, "TEST", "TEST", 0L), onDismiss)

    @Test
    fun attachesToAPlainActivityWithoutALifecycleOfItsOwn() {
        val (activity, root) = gameScreen()
        val overlay = overlay(activity)
        // Threw "ViewTreeLifecycleOwner not found" before the overlay gave the root its owner.
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.show()
        assertTrue(overlay.isShowing)
        overlay.destroy()
    }

    @Test
    fun startsHiddenAndToggles() {
        val (activity, root) = gameScreen()
        val overlay = overlay(activity)
        root.addView(overlay)
        assertFalse(overlay.isShowing)
        overlay.show()
        assertEquals(View.VISIBLE, overlay.visibility)
        overlay.hide()
        assertFalse(overlay.isShowing)
        overlay.destroy()
    }

    @Test
    fun keepsTheSecondScreensShapeCentred() {
        val (activity, root) = gameScreen()
        val overlay = overlay(activity)
        root.addView(overlay)
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        overlay.layout(0, 0, 1920, 1080)
        val screen = overlay.getChildAt(0)
        assertEquals(1240, screen.width)
        assertEquals(1080, screen.height)
        assertEquals((1920 - 1240) / 2, screen.left)
        overlay.destroy()
    }

    @Test
    fun aTapBesideTheCompanionDismissesIt() {
        val (activity, root) = gameScreen()
        var dismissed = false
        val overlay = overlay(activity) { dismissed = true }
        root.addView(overlay)
        overlay.show()
        overlay.performClick()
        assertTrue(dismissed)
        overlay.destroy()
    }
}
