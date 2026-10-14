package com.khumam.gbaduo.companion

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.khumam.gbaduo.R
import java.io.File

/**
 * Shown on the Thor's second display. Renders the assigned pack's layout, or a fallback message.
 *
 * Its window is not focusable, so touching this screen never pulls controller
 * focus away from the game on the main display (touch input still works). If a
 * key event reaches it anyway, it's handed to [onKey] so the game still gets it.
 */
class CompanionPresentation(
    context: Context,
    display: Display,
    private val gameTitle: String,
    private val gameCode: String,
    private val crc32: Long,
    private val onKey: (KeyEvent) -> Boolean,
) : Presentation(context, display) {

    private lateinit var root: FrameLayout
    private var renderer: WidgetRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        showMessage(context.getString(R.string.no_companion_pack))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)

    private fun showMessage(message: String) {
        root.removeAllViews()
        renderer = null
        val label = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            text = "$gameTitle\n$gameCode · ${"%08x".format(crc32)}\n\n$message"
        }
        root.addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { gravity = Gravity.CENTER },
        )
    }

    /**
     * Call once, from the main thread, after [show], when a pack is assigned (or re-assigned).
     * False if its layout couldn't be loaded (the screen then explains why instead).
     */
    fun showPack(manifest: PackManifest, packDir: File): Boolean {
        val view = runCatching {
            val layoutJson = File(packDir, manifest.layoutFile).readText()
            val newRenderer = WidgetRenderer(context, packDir)
            newRenderer.build(LayoutParser.parse(layoutJson)).also { renderer = newRenderer }
        }.getOrElse { error ->
            Log.e(TAG, "Could not load layout of pack '${manifest.id}'", error)
            showMessage("${manifest.name}: ${error.message}")
            return false
        }
        root.removeAllViews()
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return true
    }

    /** Call from the main thread with each new snapshot (hop off the emu thread first). */
    fun update(values: Map<String, Any?>) {
        renderer?.update(values)
    }

    private companion object {
        const val TAG = "CompanionPresentation"
    }
}
