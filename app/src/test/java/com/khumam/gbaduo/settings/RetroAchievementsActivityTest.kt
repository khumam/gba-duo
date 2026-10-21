package com.khumam.gbaduo.settings

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetroAchievementsActivityTest {

    private fun fields(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun passwordIsMasked() {
        val activity = Robolectric.buildActivity(RetroAchievementsActivity::class.java).setup().get()
        val (username, password) = fields(activity.window.decorView)
        password.setText("secret")
        // Shown as dots, not the text: setSingleLine() must not have replaced the password transformation.
        assertTrue(password.transformationMethod is PasswordTransformationMethod)
        val shown = password.transformationMethod.getTransformation(password.text, password).toString()
        assertEquals("••••••", shown)
        assertTrue(username.transformationMethod !is PasswordTransformationMethod)
    }
}
