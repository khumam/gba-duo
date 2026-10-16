package com.khumam.gbaduo.companion

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.android.AndroidCompanionPlatform
import com.khumam.gbaduo.companion.style.CssWarning
import com.khumam.gbaduo.companion.tree.CompanionTree
import com.khumam.gbaduo.companion.ui.CompanionScreen
import java.io.File

/**
 * What the companion screen shows, independent of which window shows it: the
 * assigned pack's layout ([CompanionScreen]) or a fallback message. Hosted by
 * [CompanionPresentation] when the companion is on a secondary display, or by
 * [CompanionActivity] when it is on the main display (the game is on the other one).
 *
 * Taps that change UI state (pages, `set`) are reported to [onUiChange] and script
 * actions to [onScriptCall], so the emulator thread can pass them to the pack's Lua.
 * Main thread only.
 */
class CompanionController(
    private val context: Context,
    private val gameTitle: String,
    private val gameCode: String,
    private val crc32: Long,
) {
    private sealed class Content {
        data class Message(val text: String) : Content()
        class Pack(val tree: CompanionTree, val platform: AndroidCompanionPlatform) : Content()
    }

    private var content: Content by mutableStateOf(message(context.getString(R.string.no_companion_pack)))

    /** Called on the main thread with the new UI state (`ui` in the pack's Lua). */
    var onUiChange: (Map<String, Any?>) -> Unit = {}

    /** Called on the main thread when a tap calls `actions.<name>(args...)` in the pack's Lua. */
    var onScriptCall: (name: String, args: List<Any?>) -> Unit = { _, _ -> }

    /** The current UI state, or empty when no pack is shown. */
    val uiState: Map<String, Any?> get() = (content as? Content.Pack)?.tree?.ui ?: emptyMap()

    /** The whole companion screen. */
    @Composable
    fun Content() {
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

    private fun message(text: String) = Content.Message("$gameTitle\n$gameCode · ${"%08x".format(crc32)}\n\n$text")

    /**
     * Call when a pack is assigned (or re-assigned). False if its layout couldn't
     * be loaded (the screen then explains why instead).
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
            content = message("${manifest.name}: ${error.message}")
            return false
        }
        return true
    }

    /**
     * Call with each new snapshot (hop off the emu thread first). A `ui` entry
     * returned by the script's `update()` runs as UI actions (e.g. `{ navigate = "battle" }`)
     * after the data is applied — see [CompanionTree.updateFromScript].
     */
    fun update(values: Map<String, Any?>) {
        val tree = (content as? Content.Pack)?.tree ?: return
        val warnings = mutableListOf<String>()
        tree.updateFromScript(values, warnings)
        logWarnings("update() ui", warnings)
    }

    /**
     * Call with what a script action returned: the same shape as an `onClick`
     * (e.g. `{ navigate = "detail" }`), minus `call`.
     */
    fun applyScriptResult(result: Any?) {
        val tree = (content as? Content.Pack)?.tree ?: return
        val warnings = mutableListOf<String>()
        val actions = Actions.parseScriptResult(result, warnings)
        logWarnings("script action result", warnings)
        tree.perform(actions)
    }

    companion object {
        private const val TAG = "Companion"

        /** Logs CSS/layout problems for pack authors (`adb logcat -s Companion`). */
        fun logWarnings(source: String, warnings: Collection<Any>) {
            for (w in warnings) Log.w(TAG, "$source: ${if (w is CssWarning) w.message else w}")
        }
    }
}
