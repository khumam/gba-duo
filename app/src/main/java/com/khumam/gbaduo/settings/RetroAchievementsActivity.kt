package com.khumam.gbaduo.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelButton

/**
 * Settings > RetroAchievements: log in with your retroachievements.org username and
 * password (only the returned token is kept), or log out.
 */
class RetroAchievementsActivity : PixelPage() {

    private lateinit var account: LinearLayout
    private lateinit var form: LinearLayout
    private lateinit var playerHud: TextView
    private lateinit var status: TextView
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var login: PixelButton
    private lateinit var logout: PixelButton

    private val listener: (RetroAchievements.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroAchievements.init(this)
        page(getString(R.string.ra_title), hints = listOf("▲ ▼" to "Choose", "A" to "Select", "B" to "Back"))
        note(getString(R.string.ra_intro), top = 0)

        // Logged in: who and how many points, and log out.
        account = section()
        playerHud = hud(emptyList())
        account.addView(playerHud, spaced(16, ViewGroup.LayoutParams.WRAP_CONTENT))
        logout = button(getString(R.string.ra_logout), Pixel.CORAL) { RetroAchievements.logout() }
        account.addView(logout, spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Logged out: the form. field() adds to [content], so move them into [form] after.
        form = section()
        username = field(getString(R.string.ra_username), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        password = field(getString(R.string.ra_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
            .apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
            }
        moveLastViews(count = 4, into = form) // label + field, twice
        login = button(getString(R.string.ra_login), Pixel.YELLOW) { submit() }
        form.addView(login, spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            typeface = Fonts.body(this@RetroAchievementsActivity, 600)
            textSize = 14f
            setTextColor(Pixel.CORAL)
            visibility = View.GONE
        }
        content.addView(status, spaced(14))

        content.addView(label(getString(R.string.ra_title)), spaced(28))
        note(getString(R.string.ra_softcore), top = 6)
        note(getString(R.string.ra_privacy), top = 8)

        username.setText(RetroAchievements.storedUsername.orEmpty())
    }

    override fun onStart() {
        super.onStart()
        RetroAchievements.addStateListener(listener)
    }

    override fun onStop() {
        RetroAchievements.removeStateListener(listener)
        super.onStop()
    }

    private fun section() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }.also { content.addView(it, spaced(0)) }

    private fun moveLastViews(count: Int, into: LinearLayout) {
        val moved = (content.childCount - count until content.childCount).map { content.getChildAt(it) }
        moved.forEach { v -> content.removeView(v); into.addView(v) }
    }

    private fun submit() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) return
        password.setText("")
        RetroAchievements.login(user, pass)
    }

    private fun render(state: RetroAchievements.State) {
        val loggedIn = state is RetroAchievements.State.LoggedIn
        account.visibility = if (loggedIn) View.VISIBLE else View.GONE
        form.visibility = if (loggedIn) View.GONE else View.VISIBLE
        if (state is RetroAchievements.State.LoggedIn) {
            playerHud.text = hud(listOf(
                getString(R.string.ra_player_label) to state.displayName,
                getString(R.string.ra_points_label) to state.score.toString().padStart(6, '0'),
            )).text
        }
        status.visibility = View.VISIBLE
        when (state) {
            is RetroAchievements.State.Failed -> {
                status.setTextColor(Pixel.CORAL)
                status.text = getString(R.string.ra_login_failed, state.message)
            }
            RetroAchievements.State.LoggingIn -> {
                status.setTextColor(Pixel.ON_INK_SOFT)
                status.text = getString(R.string.ra_logging_in)
            }
            else -> status.visibility = View.GONE
        }
        login.isEnabled = state != RetroAchievements.State.LoggingIn
        // Not the username field: focusing it would pop the keyboard up on every visit.
        focusFirst(if (loggedIn) logout else login)
    }
}
