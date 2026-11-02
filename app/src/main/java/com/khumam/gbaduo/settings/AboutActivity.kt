package com.khumam.gbaduo.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import com.khumam.gbaduo.BuildConfig
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.update.AppRelease
import com.khumam.gbaduo.update.AppUpdater
import com.khumam.gbaduo.update.UpdateCheck
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Settings > About: what GBADuo is, its version, source, what it's built with, and a check for a newer build. */
class AboutActivity : PixelPage() {

    private lateinit var updater: AppUpdater
    private lateinit var updateArea: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var downloaded: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.about_title), hints = listOf("A" to "Open", "B" to "Back"))

        content.addView(hud(listOf("GBADuo" to "V${BuildConfig.VERSION_NAME}", "Build" to BuildConfig.VERSION_CODE.toString().padStart(3, '0'))),
            spaced(0, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogBox(getString(R.string.app_name)) {
            addView(boxText(getString(R.string.about_tagline), 15f))
            addView(boxText(getString(R.string.about_disclaimer), 13f, top = 12))
        }

        updater = AppUpdater(UrlHttp(), File(cacheDir, "updates"))
        updateArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(updateArea, spaced(24))
        showCheckButton()

        val source = button(getString(R.string.about_source), Pixel.YELLOW) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))) }
        }
        content.addView(source, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))

        val support = button(getString(R.string.about_support), Pixel.YELLOW) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.kofi_url)))) }
        }
        content.addView(support, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(label(getString(R.string.about_credits_title)), spaced(28))
        note(getString(R.string.about_credits), top = 6)
        focusFirst(updateArea.getChildAt(0) ?: source)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showCheckButton(message: String? = null, error: Boolean = false) {
        updateArea.removeAllViews()
        val check = button(getString(R.string.update_check), Pixel.YELLOW) { check() }
        updateArea.addView(check, spaced(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (message != null) updateArea.addView(statusLine(message, error))
        check.requestFocus()
    }

    private fun statusLine(text: String, error: Boolean = false) = note(text, top = 8).also { content.removeView(it) }.apply {
        setTextColor(if (error) Pixel.CORAL else Pixel.ON_INK_SOFT)
    }

    private fun check() {
        updateArea.removeAllViews()
        updateArea.addView(busyRow(getString(R.string.update_checking)))
        io.execute {
            val result = updater.check()
            main.post {
                if (isDestroyed) return@post
                when (result) {
                    is UpdateCheck.Available -> showAvailable(result.release)
                    UpdateCheck.UpToDate -> showCheckButton(getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
                    is UpdateCheck.Failed -> showCheckButton(getString(R.string.update_failed), error = true)
                }
            }
        }
    }

    private fun showAvailable(release: AppRelease, message: String? = null) {
        updateArea.removeAllViews()
        updateArea.addView(statusLine(getString(R.string.update_available, release.version)).apply { setTextColor(Pixel.YELLOW) })
        if (release.notes.isNotEmpty()) updateArea.addView(statusLine(release.notes.take(NOTES_MAX)))
        if (message != null) updateArea.addView(statusLine(message, error = true))
        val action = button(getString(R.string.update_download), Pixel.YELLOW) { download(release) }
        updateArea.addView(action, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        action.requestFocus()
    }

    private fun download(release: AppRelease) {
        updateArea.removeAllViews()
        val progress = busyRow(getString(R.string.update_downloading, ""))
        updateArea.addView(progress)
        io.execute {
            val apk = try {
                updater.download(release) { done, total ->
                    val text = if (total > 0) "${done * 100 / total}%" else "${done / 1024} KB"
                    main.post { if (!isDestroyed) progress.findTextView()?.text = getString(R.string.update_downloading, text) }
                }
            } catch (e: IOException) {
                null
            }
            main.post {
                if (isDestroyed) return@post
                if (apk == null) showAvailable(release, getString(R.string.update_download_failed)) else {
                    downloaded = apk
                    showInstall(release, apk)
                    install(apk)
                }
            }
        }
    }

    private fun showInstall(release: AppRelease, apk: File) {
        updateArea.removeAllViews()
        updateArea.addView(statusLine(getString(R.string.update_available, release.version)).apply { setTextColor(Pixel.YELLOW) })
        val install = button(getString(R.string.update_install), Pixel.YELLOW) { install(apk) }
        updateArea.addView(install, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
        install.requestFocus()
    }

    /** Opens the system installer for [apk], or the "install unknown apps" screen first when GBADuo isn't allowed yet. */
    private fun install(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            updateArea.addView(statusLine(getString(R.string.update_permission), error = true))
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }.onFailure {
            updateArea.addView(statusLine(getString(R.string.update_install_failed), error = true))
        }
    }

    private fun android.view.View.findTextView(): android.widget.TextView? =
        (this as? LinearLayout)?.let { row -> (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.TextView>().firstOrNull() }

    private companion object {
        const val NOTES_MAX = 600
    }
}
