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
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.Stylesheet
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
            val layout = LayoutParser.parseWithWarnings(File(packDir, manifest.layoutFile).readText())
            WidgetRenderer.logWarnings(manifest.layoutFile, layout.warnings)
            val newRenderer = WidgetRenderer(context, packDir, builtInStyles(context), packStyles(manifest, packDir))
            newRenderer.build(layout.root).also {
                WidgetRenderer.logWarnings("styles", newRenderer.warnings)
                renderer = newRenderer
            }
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
        const val BUILT_IN_STYLES = "companion/default.css"

        fun builtInStyles(context: Context): List<Stylesheet> {
            val css = context.assets.open(BUILT_IN_STYLES).bufferedReader().use { it.readText() }
            return listOf(CssParser.parse(css, sourceName = BUILT_IN_STYLES))
        }

        /** The manifest's `styles`, in order; a missing file or one outside the pack is skipped with a warning. */
        fun packStyles(manifest: PackManifest, packDir: File): List<Stylesheet> = manifest.styleFiles.mapNotNull { path ->
            val file = CssParser.resolveUrl(path, packDir, packDir)?.takeIf { it.isFile }
            if (file == null) {
                Log.w(TAG, "Pack '${manifest.id}': stylesheet '$path' not found")
                return@mapNotNull null
            }
            CssParser.parse(file.readText(), baseDir = file.parentFile, packDir = packDir, sourceName = path).also {
                WidgetRenderer.logWarnings(path, it.warnings)
            }
        }
    }
}
