package com.khumam.gbaduo.companion

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.android.AndroidCompanionPlatform
import com.khumam.gbaduo.companion.android.PresentationComposeOwner
import com.khumam.gbaduo.companion.style.CssWarning
import com.khumam.gbaduo.companion.tree.CompanionTree
import com.khumam.gbaduo.companion.ui.CompanionScreen
import java.io.File

/**
 * Shown on the Thor's second display, in Jetpack Compose: the assigned pack's
 * layout ([CompanionScreen]), or a fallback message.
 *
 * Taps that change UI state (pages, `set`) are reported to [onUiChange] and script
 * actions to [onScriptCall], so the emulator thread can pass them to the pack's Lua.
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

    private sealed class Content {
        data class Message(val text: String) : Content()
        class Pack(val tree: CompanionTree, val platform: AndroidCompanionPlatform) : Content()
    }

    private val owner = PresentationComposeOwner()
    private var content: Content by mutableStateOf(Content.Message(""))

    /** Called on the main thread with the new UI state (`ui` in the pack's Lua). */
    var onUiChange: (Map<String, Any?>) -> Unit = {}

    /** Called on the main thread when a tap calls `actions.<name>(args...)` in the pack's Lua. */
    var onScriptCall: (name: String, args: List<Any?>) -> Unit = { _, _ -> }

    /** The current UI state, or empty when no pack is shown. */
    val uiState: Map<String, Any?> get() = (content as? Content.Pack)?.tree?.ui ?: emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    when (val c = content) {
                        is Content.Pack -> CompanionScreen(c.tree, c.platform)
                        is Content.Message -> BasicText(
                            text = c.text,
                            modifier = Modifier.align(Alignment.Center),
                            style = TextStyle(color = Color.LightGray, fontSize = 16.sp, textAlign = TextAlign.Center),
                        )
                    }
                }
            }
        }
        owner.attach(view)
        setContentView(view)
        goFullScreen()
        if ((content as? Content.Message)?.text.isNullOrEmpty()) showMessage(context.getString(R.string.no_companion_pack))
    }

    override fun onStop() {
        owner.destroy()
        super.onStop()
    }

    /**
     * The whole second screen belongs to the companion: no status bar and no
     * navigation bar / gesture handle over the pack's layout. A swipe from an
     * edge still shows them briefly. Uses only platform APIs (minSdk 30).
     */
    private fun goFullScreen() {
        val window = window ?: return
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)

    private fun showMessage(message: String) {
        content = Content.Message("$gameTitle\n$gameCode · ${"%08x".format(crc32)}\n\n$message")
    }

    /**
     * Call once, from the main thread, after [show], when a pack is assigned (or re-assigned).
     * False if its layout couldn't be loaded (the screen then explains why instead).
     */
    fun showPack(manifest: PackManifest, packDir: File): Boolean {
        content = runCatching {
            val layout = LayoutParser.parseWithWarnings(File(packDir, manifest.layoutFile).readText())
            logWarnings(manifest.layoutFile, layout.warnings)
            val styles = PackStyles.forPack(manifest, packDir)
            val builtIn = PackStyles.builtIn(context)
            val platform = AndroidCompanionPlatform(packDir, (builtIn + styles).flatMap { it.fontFaces })
            val tree = CompanionTree(layout.root, builtIn, styles, platform::loadImage)
            tree.onUiChange = { onUiChange(it) }
            tree.onCall = { name, args -> onScriptCall(name, args) }
            logWarnings("styles", tree.warnings)
            Content.Pack(tree, platform)
        }.getOrElse { error ->
            Log.e(TAG, "Could not load layout of pack '${manifest.id}'", error)
            showMessage("${manifest.name}: ${error.message}")
            return false
        }
        return true
    }

    /** Call from the main thread with each new snapshot (hop off the emu thread first). */
    fun update(values: Map<String, Any?>) {
        (content as? Content.Pack)?.tree?.update(values)
    }

    /**
     * Call from the main thread with what a script action returned: the same
     * shape as an `onClick` (e.g. `{ navigate = "detail" }`), minus `call`.
     */
    fun applyScriptResult(result: Any?) {
        val tree = (content as? Content.Pack)?.tree ?: return
        val warnings = mutableListOf<String>()
        val actions = Actions.parse(result, warnings).filter { action ->
            (action !is Action.Call).also { if (!it) warnings += "a script action can't 'call' another; call it directly in Lua" }
        }
        logWarnings("script action result", warnings)
        tree.perform(actions)
    }

    companion object {
        private const val TAG = "CompanionPresentation"

        /** Logs CSS/layout problems for pack authors (`adb logcat -s CompanionPresentation`). */
        fun logWarnings(source: String, warnings: Collection<Any>) {
            for (w in warnings) Log.w(TAG, "$source: ${if (w is CssWarning) w.message else w}")
        }
    }
}
