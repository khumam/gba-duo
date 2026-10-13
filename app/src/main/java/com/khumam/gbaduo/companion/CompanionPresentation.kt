package com.khumam.gbaduo.companion

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/** Shown on the Thor's second display. Renders the assigned pack's layout, or a fallback message. */
class CompanionPresentation(
    context: Context,
    display: Display,
    private val gameTitle: String,
    private val gameCode: String,
    private val crc32: Long,
) : Presentation(context, display) {

    private lateinit var root: FrameLayout
    private var renderer: WidgetRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        showNoPackMessage()
    }

    private fun showNoPackMessage() {
        root.removeAllViews()
        val message = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            text = "$gameTitle\n$gameCode · ${crc32.toString(16)}\n\nNo companion pack"
        }
        root.addView(
            message,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { gravity = Gravity.CENTER },
        )
    }

    /** Call once, from the main thread, when a pack is assigned (or re-assigned). */
    fun showPack(manifest: PackManifest, packDir: File) {
        val layoutJson = File(packDir, manifest.layoutFile).readText()
        val widget = LayoutParser.parse(layoutJson)
        val newRenderer = WidgetRenderer(context, packDir)
        val view = newRenderer.build(widget)
        root.removeAllViews()
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        renderer = newRenderer
    }

    /** Call from the main thread with each new snapshot (hop off the emu thread first). */
    fun update(values: Map<String, Any?>) {
        renderer?.update(values)
    }
}
