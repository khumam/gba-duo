package com.khumam.gbaduo.settings

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.emu.InUse
import com.khumam.gbaduo.emu.ShaderChoice
import com.khumam.gbaduo.emu.ShaderLibrary
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.ShaderSettings
import com.khumam.gbaduo.emu.shaderlib.ShaderCatalogStore
import com.khumam.gbaduo.emu.shaderlib.ShaderDownloader
import com.khumam.gbaduo.emu.shaderlib.ShaderListing
import com.khumam.gbaduo.emu.shaderlib.ShaderTree
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.storage.GameSettings
import com.khumam.gbaduo.storage.GameSettingsKeys
import java.io.File
import java.util.concurrent.Executors

/**
 * Settings > Shader and Library > Game settings > Shader: what shader is in use, the built-in ones, and the libretro
 * slang library browsed folder by folder (every folder is one more page on the back stack). Opened with a game's folder
 * ([EXTRA_GAME_DIR]) it picks for that game; without, it picks the default every game uses.
 */
class ShaderActivity : PixelPage() {

    private var gameDir: String? = null
    private var folder: String? = null          // null: the home page; "": the library's root; else a folder of it
    private var settings: GameSettings? = null
    private lateinit var library: ShaderLibrary
    private lateinit var store: ShaderCatalogStore
    private lateinit var downloader: ShaderDownloader
    private lateinit var list: LinearLayout
    private val io = Executors.newSingleThreadExecutor()

    private var listing: ShaderListing? = null
    private var tree: ShaderTree? = null
    private var listState = ShaderPageModel.ListState.LOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameDir = intent.getStringExtra(EXTRA_GAME_DIR)
        folder = intent.getStringExtra(EXTRA_FOLDER)
        settings = gameDir?.let { GameSettings(File(it, "settings.json")) }
        library = ShaderLibrary.of(this)
        val http = UrlHttp()
        store = ShaderCatalogStore(http, File(filesDir, "shaders/catalog.json"))
        downloader = ShaderDownloader(http, library, File(cacheDir, "shader-download"))

        val title = when (val name = ShaderPageModel.folderName(folder)) {
            null -> getString(R.string.shader_title)
            "" -> getString(R.string.shader_library_title)
            else -> name
        }
        page(title, listOf("▲ ▼" to "Choose", "A" to "Pick", "B" to "Back"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, spaced(0))
        if (folder != null) loadList(refresh = false) else render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun inUse(): InUse = ShaderSettings.inUse(this, settings)

    // ---- the list of library shaders --------------------------------------------------------

    private fun loadList(refresh: Boolean) {
        listState = ShaderPageModel.ListState.LOADING
        render()
        io.execute {
            val result = store.load(refresh)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Fetch.Ok -> {
                        listing = result.value
                        tree = treeFor(result.value)
                        listState = ShaderPageModel.ListState.READY
                    }
                    is Fetch.Failed -> listState = ShaderPageModel.ListState.UNAVAILABLE
                }
                render()
                if (result is Fetch.Failed) addError(failText(result), retry = true)
            }
        }
    }

    // ---- drawing the rows ---------------------------------------------------------------------

    private fun render() {
        list.removeAllViews()
        busySummaries.clear()
        var first: View? = null
        for (row in ShaderPageModel.rows(folder, tree, listState, library, inUse())) {
            val view = addRow(row)
            if (first == null && view != null && view.isFocusable) first = view
        }
        if (folder == "" && listState != ShaderPageModel.ListState.LOADING) {
            val refresh = button(getString(R.string.shader_list_refresh), Pixel.LAVENDER) { loadList(refresh = true) }
            list.addView(refresh, spaced(16, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (first == null) first = refresh
        }
        first?.let { focusFirst(it) }
    }

    /** A card made by [menuCard], moved from the page into [list]. */
    private fun card(icon: Int, tile: Int, title: String, summary: String, onClick: () -> Unit): Pair<View, TextView> {
        val made = menuCard(icon, tile, title, summary, onClick)
        content.removeView(made.first)
        list.addView(made.first, spaced(12))
        return made
    }

    private fun addRow(row: ShaderRow): View? = when (row) {
        is ShaderRow.InUseCard -> { addInUse(row.inUse); null }
        is ShaderRow.Header -> {
            val text = getString(if (row.kind == ShaderPageModel.HeaderKind.BUILTIN) R.string.shader_header_builtin else R.string.shader_header_library)
            list.addView(label(text), spaced(20))
            null
        }
        is ShaderRow.Builtin -> {
            val name = if (row.preset == ShaderPreset.OFF && gameDir != null) {
                getString(R.string.shader_follow_default, ShaderSettings.default(this).displayName)
            } else row.preset.displayName
            card(R.drawable.ic_settings, Pixel.YELLOW, name, if (row.active) getString(R.string.shader_state_in_use) else "") {
                apply(ShaderChoice.Builtin(row.preset))
            }.first
        }
        is ShaderRow.LibraryEntry -> {
            val supported = supportsLibraryShaders()
            card(R.drawable.ic_folders, Pixel.MINT, getString(R.string.shader_library_entry),
                getString(if (supported) R.string.shader_library_summary else R.string.shader_library_needs_es3)) {
                if (supported) openFolder("") else Toast.makeText(this, R.string.shader_library_needs_es3, Toast.LENGTH_LONG).show()
            }.first
        }
        is ShaderRow.Folder -> card(R.drawable.ic_folders, Pixel.LAVENDER, row.node.name, "") { openFolder(row.node.path) }.first
        is ShaderRow.Preset -> addPreset(row)
        is ShaderRow.Message -> {
            val text = getString(if (row.kind == ShaderPageModel.MessageKind.LOADING) R.string.shader_list_loading else R.string.shader_list_unavailable)
            if (row.kind == ShaderPageModel.MessageKind.LOADING) list.addView(busyRow(text), spaced(12))
            else list.addView(note(text, top = 8).also { content.removeView(it) }, spaced(8))
            null
        }
    }

    private fun addInUse(inUse: InUse) {
        val choice = inUse.choice
        val origin = if (choice is ShaderChoice.Library) choice.path else getString(R.string.shader_origin_builtin)
        list.addView(hud(listOf(getString(R.string.shader_in_use) to choice.displayName, getString(R.string.shader_from) to origin)), spaced(0))
        val why = getString(when (inUse.source) {
            InUse.Source.GAME -> R.string.shader_why_game
            InUse.Source.DEFAULT -> R.string.shader_why_default
            InUse.Source.NONE -> R.string.shader_why_none
        })
        list.addView(note(why, top = 8).also { content.removeView(it) }, spaced(8))
        inUse.missing?.let {
            list.addView(note(getString(R.string.shader_missing, it.displayName), top = 8).also { v ->
                content.removeView(v)
                v.setTextColor(Pixel.CORAL)
            }, spaced(4))
        }
    }

    private fun addPreset(row: ShaderRow.Preset): View {
        val path = row.node.path
        val summaryRes = when {
            row.active -> R.string.shader_state_in_use
            row.downloaded -> R.string.shader_state_downloaded
            else -> R.string.shader_state_get
        }
        val (view, summary) = card(R.drawable.ic_settings, if (row.downloaded) Pixel.YELLOW else Pixel.PINK, row.node.name, getString(summaryRes)) {
            if (row.downloaded) apply(ShaderChoice.Library(path)) else downloadAndApply(path, row.node.name)
        }
        if (row.downloaded) {
            view.setOnLongClickListener { confirmRemove(path, row.node.name); true }
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && event.action == KeyEvent.ACTION_DOWN) { confirmRemove(path, row.node.name); true } else false
            }
        }
        busySummaries[path] = summary
        downloads[path]?.let { (done, total) -> summary.text = getString(R.string.shader_downloading, done, total) }
        return view
    }

    private val busySummaries = HashMap<String, TextView>()

    /** Presets downloading now (UI thread only): files done and known. Rows are rebuilt on every render, so progress is looked up, not kept in a view. */
    private val downloads = HashMap<String, Pair<Int, Int>>()

    private fun addError(text: String, retry: Boolean) {
        list.addView(note(text, top = 8).also { content.removeView(it); it.setTextColor(Pixel.CORAL) }, spaced(8))
        if (retry) {
            val again = button(getString(R.string.shader_retry), Pixel.YELLOW) { loadList(refresh = true) }
            list.addView(again, spaced(12, ViewGroup.LayoutParams.WRAP_CONTENT))
            focusFirst(again)
        }
    }

    private fun failText(failed: Fetch.Failed): String = when (failed.reason) {
        Fetch.Reason.OFFLINE -> getString(R.string.shader_err_offline)
        Fetch.Reason.TOO_BIG -> getString(R.string.shader_err_too_big)
        Fetch.Reason.INSTALL_FAILED, Fetch.Reason.UNSUPPORTED_CATALOG, Fetch.Reason.BAD_CHECKSUM ->
            getString(R.string.shader_err_install, failed.detail.orEmpty())
        Fetch.Reason.DOWNLOAD_FAILED -> getString(R.string.shader_err_http, failed.detail.orEmpty())
    }

    // ---- actions --------------------------------------------------------------------------------

    private fun openFolder(path: String) {
        startActivity(Intent(this, ShaderActivity::class.java).putExtra(EXTRA_GAME_DIR, gameDir).putExtra(EXTRA_FOLDER, path))
    }

    /** Saves the pick: for this game when opened from Game settings, else as the default for every game. */
    private fun apply(choice: ShaderChoice) {
        val game = settings
        if (game != null) game.put(GameSettingsKeys.SHADER, choice.takeIf { it != ShaderChoice.OFF }?.id)
        else ShaderSettings.setDefault(this, choice)
        Toast.makeText(this, getString(R.string.shader_applied, choice.displayName), Toast.LENGTH_SHORT).show()
        render()
    }

    private fun downloadAndApply(path: String, name: String) {
        val sha = listing?.sha ?: return
        if (path in downloads) return       // already on its way: a second tap would fetch it all again
        downloads[path] = 0 to 1
        io.execute {
            val result = downloader.install(path, sha) { done, total ->
                runOnUiThread {
                    if (path !in downloads) return@runOnUiThread
                    downloads[path] = done to total
                    busySummaries[path]?.text = getString(R.string.shader_downloading, done, total)
                }
            }
            runOnUiThread {
                downloads.remove(path)
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is Fetch.Ok -> apply(ShaderChoice.Library(path))
                    is Fetch.Failed -> busySummaries[path]?.let {
                        it.text = failText(result)
                        it.setTextColor(Pixel.CORAL)
                    }
                }
            }
        }
        busySummaries[path]?.text = getString(R.string.shader_downloading, 0, 1)
    }

    private fun confirmRemove(path: String, name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.shader_remove_confirm, name))
            .setPositiveButton(R.string.shader_remove) { _, _ ->
                library.delete(path)
                Toast.makeText(this, getString(R.string.shader_removed, name), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Library shaders are translated to GLSL ES 3.x, so the device needs OpenGL ES 3.0 or newer. */
    private fun supportsLibraryShaders(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        /** The folder tree of the last list, kept across pages: every folder is its own page and would build it again. */
        private var lastTree: Pair<ShaderListing, ShaderTree>? = null

        private fun treeFor(listing: ShaderListing): ShaderTree =
            lastTree?.takeIf { it.first === listing }?.second ?: ShaderTree(listing.presets).also { lastTree = listing to it }

        const val EXTRA_GAME_DIR = "game_dir"
        const val EXTRA_FOLDER = "folder"
    }
}
