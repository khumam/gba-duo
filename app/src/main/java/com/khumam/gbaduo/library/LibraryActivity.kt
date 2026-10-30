package com.khumam.gbaduo.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.companion.PackInstaller
import com.khumam.gbaduo.companion.PackManifest
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.library.ui.CartridgeCarousel
import com.khumam.gbaduo.library.ui.DialogBoxDrawable
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.GraphPaperDrawable
import com.khumam.gbaduo.library.ui.OutlineText
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable
import com.khumam.gbaduo.library.ui.PixelButton
import com.khumam.gbaduo.player.PlayerActivity
import com.khumam.gbaduo.settings.PackCatalogActivity
import com.khumam.gbaduo.settings.RomFoldersActivity
import com.khumam.gbaduo.settings.SettingsActivity
import com.khumam.gbaduo.settings.GameSettingsActivity
import com.khumam.gbaduo.storage.GameStorage
import com.khumam.gbaduo.storage.LegacyMigration
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The library: your games as cartridges on a shelf, one centred and selected. Built for the
 * controller first (D-pad browses, A plays, Y opens the companion pack, Start opens
 * Settings) and for touch (tap a cartridge to centre it, tap it again to play).
 * Look: gbaduo-web/DESIGN.md (cream graph paper, ink outlines, hard shadows, Silkscreen).
 */
class LibraryActivity : Activity() {

    private lateinit var library: RomLibrary
    private lateinit var carousel: CartridgeCarousel

    private lateinit var recycler: RecyclerView
    private lateinit var pointer: TextView
    private lateinit var hud: TextView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: PixelButton
    private lateinit var info: View
    private lateinit var actions: View
    private lateinit var gameTitle: OutlineText
    private lateinit var tagPlatform: TextView
    private lateinit var tagCode: TextView
    private lateinit var tagPack: TextView
    private lateinit var fileName: TextView
    private lateinit var hints: TextView
    private lateinit var position: TextView

    private val packsDir by lazy { File(filesDir, "packs") }
    private val packStore by lazy { PackStore(packsDir) }
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private var pickPackFor: GameStorage? = null

    /** Folder scans and ROM hashing are blocking SAF I/O; one background thread keeps them ordered. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var refreshGeneration = 0

    /** The Thor's bottom screen (~538dp wide) and other narrow windows get a tighter layout. */
    private val compact by lazy { resources.configuration.screenWidthDp < COMPACT_WIDTH_DP }

    private val blink = Handler(Looper.getMainLooper())
    private val motion by lazy { Pixel.motionEnabled(this) }
    private val blinkStep = object : Runnable {
        override fun run() {
            // steps(2): the pointer is either there or not, no fade.
            pointer.alpha = if (pointer.alpha > 0f) 0f else 1f
            blink.postDelayed(this, BLINK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        library = RomLibrary(this)
        RetroAchievements.init(this) // logs back in with a stored token, if you've logged in before
        bindViews()
        styleViews()

        carousel = CartridgeCarousel(recycler, onSelected = ::showSelected, onPlay = { play() })

        findViewById<PixelButton>(R.id.open_settings).setOnClickListener { openSettings() }
        findViewById<PixelButton>(R.id.play).setOnClickListener { play() }
        findViewById<PixelButton>(R.id.pack_button).setOnClickListener { selectedEntry()?.let(::showCompanionMenu) }
        findViewById<PixelButton>(R.id.game_settings_button).setOnClickListener { selectedEntry()?.let(::openGameSettings) }
        emptyAction.setOnClickListener { startActivity(Intent(this, RomFoldersActivity::class.java)) }
    }

    private fun bindViews() {
        recycler = findViewById(R.id.carousel)
        pointer = findViewById(R.id.pointer)
        hud = findViewById(R.id.hud)
        empty = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.empty_title)
        emptyText = findViewById(R.id.empty_text)
        emptyAction = findViewById(R.id.empty_action)
        info = findViewById(R.id.info)
        actions = findViewById(R.id.actions)
        gameTitle = findViewById(R.id.game_title)
        tagPlatform = findViewById(R.id.tag_platform)
        tagCode = findViewById(R.id.tag_code)
        tagPack = findViewById(R.id.tag_pack)
        fileName = findViewById(R.id.file_name)
        hints = findViewById(R.id.hints)
        position = findViewById(R.id.position)
    }

    private fun styleViews() {
        window.setBackgroundDrawable(GraphPaperDrawable(this))
        window.statusBarColor = Pixel.CREAM
        window.navigationBarColor = Pixel.INK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val display = Fonts.display(this)
        val displayBold = Fonts.display(this, bold = true)
        val body = Fonts.body(this)

        findViewById<OutlineText>(R.id.wordmark).apply {
            text = getString(R.string.app_name).uppercase()
            accentFrom = text.length - "DUO".length // GBA in purple, DUO in yellow
            setTextSizeSp(if (compact) 22f else 30f)
        }
        gameTitle.apply {
            centered = true
            setTextSizeSp(if (compact) 20f else 26f)
            shadowPx = Pixel.dp(this@LibraryActivity, 3f)
            // Long names shrink to fit before they're cut off.
            minTextSizePx = 14f * resources.displayMetrics.scaledDensity
        }
        if (compact) {
            val gutter = Pixel.dp(this, 16f).toInt()
            (hud.parent as View).setPadding(gutter, (hud.parent as View).paddingTop, gutter, 0)
            (hud.layoutParams as LinearLayout.LayoutParams).marginEnd = Pixel.dp(this, 10f).toInt()
            info.setPadding(gutter, 0, gutter, 0)
            findViewById<View>(R.id.hints_bar).setPadding(gutter, 0, gutter, 0)
            // Three buttons side by side: smaller text and gaps so none wraps or runs off the edge.
            actions.setPadding(gutter, 0, gutter, 0)
            val gap = Pixel.dp(this, 10f).toInt()
            findViewById<PixelButton>(R.id.play).apply {
                val narrow = Pixel.dp(this@LibraryActivity, 96f).toInt()
                setMinWidth(narrow)
                minimumWidth = narrow
                textSize = 14f
            }
            findViewById<PixelButton>(R.id.pack_button).apply {
                textSize = 14f
                (layoutParams as LinearLayout.LayoutParams).marginStart = gap
            }
            findViewById<PixelButton>(R.id.game_settings_button).apply {
                textSize = 14f
                (layoutParams as LinearLayout.LayoutParams).marginStart = gap
            }
        }
        // The empty / scanning box: 560dp, or the screen width minus a margin when narrower.
        empty.layoutParams = empty.layoutParams.apply {
            width = minOf(Pixel.dp(this@LibraryActivity, 560f), resources.displayMetrics.widthPixels - Pixel.dp(this@LibraryActivity, 48f)).toInt()
        }

        hud.apply { background = ColorDrawable(Pixel.INK); typeface = display; textSize = 12f; letterSpacing = 0.06f }
        findViewById<PixelButton>(R.id.open_settings).apply {
            style(Pixel.CREAM)
            textSize = 13f
            minHeight = Pixel.dp(this@LibraryActivity, 44f).toInt()
        }
        findViewById<PixelButton>(R.id.play).style(Pixel.YELLOW)
        findViewById<PixelButton>(R.id.pack_button).style(Pixel.CREAM)
        findViewById<PixelButton>(R.id.game_settings_button).style(Pixel.CREAM)

        pointer.apply { typeface = displayBold; textSize = 18f; setTextColor(Pixel.PINK); setShadowLayer(0.01f, 2f, 2f, Pixel.INK) }

        tagPlatform.apply { background = ColorDrawable(Pixel.INK); typeface = display; textSize = 12f; setTextColor(Pixel.YELLOW); letterSpacing = 0.1f }
        tagCode.apply {
            background = PixelBoxDrawable(this@LibraryActivity, Pixel.YELLOW_SOFT, shadowDp = 0f, borderDp = 2f)
            typeface = Fonts.body(this@LibraryActivity, 600); textSize = 12f; setTextColor(Pixel.INK)
        }
        tagPack.apply {
            background = PixelBoxDrawable(this@LibraryActivity, Pixel.PINK, shadowDp = 0f, borderDp = 2f)
            typeface = display; textSize = 11f; setTextColor(Pixel.INK); letterSpacing = 0.06f
        }
        fileName.apply { typeface = body; textSize = 12f; setTextColor(Pixel.INK_SOFT) }

        findViewById<View>(R.id.hints_bar).background = ColorDrawable(Pixel.INK)
        hints.apply { typeface = display; textSize = 11f; letterSpacing = 0.06f }
        position.apply { typeface = displayBold; textSize = 12f; setTextColor(Pixel.YELLOW); letterSpacing = 0.08f }

        empty.background = DialogBoxDrawable(this)
        emptyTitle.apply {
            background = PixelBoxDrawable(this@LibraryActivity, Pixel.YELLOW, shadowDp = 3f, borderDp = 3f)
            typeface = displayBold; textSize = 14f; setTextColor(Pixel.INK); letterSpacing = 0.08f
        }
        emptyText.apply { typeface = Fonts.body(this@LibraryActivity, 500); textSize = 14f; setTextColor(Pixel.INK); setLineSpacing(0f, 1.4f) }
        emptyAction.style(Pixel.YELLOW)
        // The label tab overlaps the box's top edge, like the banner's dialog box, so the
        // box must not clip it.
        empty.clipChildren = false
        empty.clipToPadding = false
        (empty.parent as android.view.ViewGroup).clipChildren = false
        emptyTitle.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            v.translationY = -(bottom - top) / 2f
        }
        renderHints(hasGames = false, canAddFolder = true)
    }

    override fun onResume() {
        super.onResume()
        carousel.reloadArtwork()
        refresh()
        if (motion) blink.postDelayed(blinkStep, BLINK_MS)
    }

    override fun onPause() {
        blink.removeCallbacks(blinkStep)
        pointer.alpha = 1f
        super.onPause()
    }

    override fun onDestroy() {
        carousel.release()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- controller ---------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> carousel.step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> carousel.step(1)
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> carousel.step(-JUMP)
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> carousel.step(JUMP)
            KeyEvent.KEYCODE_MOVE_HOME -> carousel.step(-carousel.items.size)
            KeyEvent.KEYCODE_MOVE_END -> carousel.step(carousel.items.size)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    if (carousel.items.isNotEmpty()) play()
                    else if (emptyAction.visibility == View.VISIBLE) emptyAction.performClick()
                }
            }
            KeyEvent.KEYCODE_BUTTON_Y -> if (event.repeatCount == 0) selectedEntry()?.let(::showCompanionMenu)
            KeyEvent.KEYCODE_BUTTON_X -> if (event.repeatCount == 0) selectedEntry()?.let(::openGameSettings)
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) openSettings()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ---- selection ------------------------------------------------------------------

    private fun selectedEntry(): RomEntry? = carousel.items.getOrNull(carousel.selected)

    private fun showSelected(index: Int) {
        val entry = carousel.items.getOrNull(index) ?: return
        prefs.edit().putString(KEY_LAST_SELECTED, entry.fileName).apply()
        gameTitle.text = GameTitle.display(entry.fileName, entry.title)
        tagPlatform.text = entry.platform.label
        tagCode.text = entry.gameCode
        tagCode.visibility = if (entry.gameCode.isBlank()) View.GONE else View.VISIBLE
        tagPack.text = getString(R.string.tag_pack)
        tagPack.visibility = if (carousel.hasPack(entry)) View.VISIBLE else View.GONE
        fileName.text = entry.fileName
        position.text = getString(R.string.position_of, pad(index + 1), pad(carousel.items.size))
    }

    private fun play() {
        val entry = selectedEntry() ?: return
        val uri = entry.uri ?: return
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_ROM_URI, uri.toString())
        intent.putExtra(PlayerActivity.EXTRA_ROM_FILE_NAME, entry.fileName)
        startActivity(intent)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    // ---- library --------------------------------------------------------------------

    private fun refresh() {
        val generation = ++refreshGeneration
        val hasFolders = library.folders.isNotEmpty()
        if (carousel.items.isEmpty()) {
            if (hasFolders) showEmpty(R.string.empty_scanning_title, getString(R.string.scanning), action = false)
            else showEmpty(R.string.empty_no_folder_title, getString(R.string.empty_no_folder_text), action = true)
        }
        background(work = {
            val entries = library.list()
            // Packs match by game code; a pack that also pins CRCs is checked when you open its menu.
            val codes = packStore.list().flatMap { it.gameCodes }.toSet()
            entries to codes
        }) { result ->
            if (generation != refreshGeneration) return@background // a newer refresh superseded this one
            val (entries, codes) = result.getOrDefault(emptyList<RomEntry>() to emptySet())
            carousel.submit(entries, codes, keepFileName = prefs.getString(KEY_LAST_SELECTED, null))
            updateHud(entries)
            when {
                entries.isNotEmpty() -> showShelf()
                !hasFolders -> showEmpty(R.string.empty_no_folder_title, getString(R.string.empty_no_folder_text), action = true)
                else -> showEmpty(
                    R.string.empty_no_games_title,
                    resources.getQuantityString(R.plurals.no_roms, library.folders.size, library.folders.size),
                    action = true,
                )
            }
        }
    }

    private fun showShelf() {
        empty.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        pointer.visibility = View.VISIBLE
        info.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        renderHints(hasGames = true)
    }

    private fun showEmpty(titleRes: Int, text: String, action: Boolean) {
        emptyTitle.setText(titleRes)
        emptyText.text = text
        emptyAction.visibility = if (action) View.VISIBLE else View.GONE
        empty.visibility = View.VISIBLE
        recycler.visibility = View.INVISIBLE
        pointer.visibility = View.INVISIBLE
        // Gone, not invisible: the box gets the whole middle of the screen.
        info.visibility = View.GONE
        actions.visibility = View.GONE
        position.text = ""
        renderHints(hasGames = false, canAddFolder = action)
    }

    /** The top-right readout: how many games, per platform. Facts only, zero-padded. */
    private fun updateHud(entries: List<RomEntry>) {
        val sb = SpannableStringBuilder()
        fun cell(label: String, value: Int) {
            if (sb.isNotEmpty()) sb.append("   ")
            sb.color(label, Pixel.ON_INK_SOFT).append(' ').color(pad(value), Pixel.ON_INK)
        }
        cell(getString(R.string.hud_games), entries.size)
        if (compact) { hud.text = sb; return }
        for (p in Platform.entries) {
            val n = entries.count { it.platform == p }
            if (n > 0) cell(p.label, n)
        }
        hud.text = sb
    }

    /** Bottom strip: the buttons that do something right now; keys in yellow. */
    private fun renderHints(hasGames: Boolean, canAddFolder: Boolean = false) {
        val sb = SpannableStringBuilder()
        fun hint(key: String, label: String) {
            if (sb.isNotEmpty()) sb.append("     ")
            sb.color(key, Pixel.YELLOW).append(' ').color(label, Pixel.ON_INK_SOFT)
        }
        if (hasGames) {
            hint("◀ ▶", getString(R.string.hint_browse))
            hint("A", getString(R.string.play))
            hint("Y", getString(R.string.hint_pack))
            if (!compact) hint("X", getString(R.string.hint_game_settings))
            if (!compact) hint("L R", getString(R.string.hint_jump))
        } else if (canAddFolder) {
            hint("A", getString(R.string.add_rom_folder))
        }
        if (!compact || !hasGames) hint("START", getString(R.string.settings_title))
        hints.text = sb
    }

    private fun SpannableStringBuilder.color(text: String, color: Int): SpannableStringBuilder {
        val start = length
        append(text.uppercase())
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    private fun pad(n: Int) = n.toString().padStart(3, '0')

    // ---- companion packs ------------------------------------------------------------

    /** Runs [work] on [io], then [onResult] on the main thread unless the activity has gone away. */
    private fun <T> background(work: () -> T, onResult: (Result<T>) -> Unit) {
        if (io.isShutdown) return
        io.execute {
            val result = runCatching(work)
            runOnUiThread { if (!isDestroyed) onResult(result) }
        }
    }

    private fun showCompanionMenu(entry: RomEntry) {
        background(
            work = {
                // The game's folder is keyed by CRC; migrating here means an assignment made
                // before the new save layout still shows (and is kept) in this menu.
                val crc = library.crc32(entry) ?: error("Could not read ${entry.fileName}")
                val storage = GameStorage.forGame(filesDir, entry.platform, entry.title, entry.gameCode, crc)
                LegacyMigration.migrate(filesDir, entry.fileName, crc, storage)
                Triple(crc, storage, packStore.matching(entry.gameCode, crc))
            },
            onResult = { result ->
                result.onSuccess { (_, storage, matches) -> showCompanionDialog(entry, storage, matches) }
                    .onFailure { Toast.makeText(this, getString(R.string.load_failed, entry.fileName), Toast.LENGTH_SHORT).show() }
            },
        )
    }

    /** Opens the game's settings page once its folder (keyed by ROM checksum) is known. */
    private fun openGameSettings(entry: RomEntry) {
        background(
            work = {
                val crc = library.crc32(entry) ?: error("Could not read ${entry.fileName}")
                val storage = GameStorage.forGame(filesDir, entry.platform, entry.title, entry.gameCode, crc)
                LegacyMigration.migrate(filesDir, entry.fileName, crc, storage)
                crc to storage.ensureDirs()
            },
            onResult = { result ->
                result.onSuccess { (crc, storage) ->
                    startActivity(
                        Intent(this, GameSettingsActivity::class.java)
                            .putExtra(GameSettingsActivity.EXTRA_GAME_DIR, storage.dir.absolutePath)
                            .putExtra(GameSettingsActivity.EXTRA_TITLE, GameTitle.display(entry.fileName, entry.title))
                            .putExtra(GameSettingsActivity.EXTRA_PLATFORM, entry.platform.label)
                            .putExtra(GameSettingsActivity.EXTRA_PLATFORM_ID, entry.platform.id)
                            .putExtra(GameSettingsActivity.EXTRA_FILE_NAME, entry.fileName)
                            .putExtra(GameSettingsActivity.EXTRA_CODE, entry.gameCode)
                            .putExtra(GameSettingsActivity.EXTRA_CRC, crc),
                    )
                }.onFailure { Toast.makeText(this, getString(R.string.load_failed, entry.fileName), Toast.LENGTH_SHORT).show() }
            },
        )
    }

    private fun showCompanionDialog(entry: RomEntry, storage: GameStorage, matches: List<PackManifest>) {
        val assignedId = storage.packId
        val labels = mutableListOf(getString(R.string.no_pack))
        labels += matches.map { pack ->
            val name = if (pack.id == assignedId) "✓ ${pack.name}" else pack.name
            if (pack.canWriteMemory) "$name · ${getString(R.string.pack_modifies_game)}" else name
        }
        labels += getString(R.string.import_pack)
        labels += getString(R.string.pack_web_get)

        AlertDialog.Builder(this)
            .setTitle(R.string.companion_pack)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> storage.packId = null
                    labels.size - 2 -> {
                        pickPackFor = storage
                        pickPackZip()
                    }
                    labels.size - 1 -> {
                        pickPackFor = storage
                        startActivityForResult(
                            Intent(this, PackCatalogActivity::class.java)
                                .putExtra(PackCatalogActivity.EXTRA_GAME_CODE, entry.gameCode)
                                .putExtra(PackCatalogActivity.EXTRA_TITLE, GameTitle.display(entry.fileName, entry.title)),
                            REQUEST_WEB_PACK,
                        )
                    }
                    else -> storage.packId = matches[which - 1].id
                }
            }
            .show()
    }

    private fun pickPackZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(intent, REQUEST_PICK_PACK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_PACK) handlePackPicked(resultCode, data)
        if (requestCode == REQUEST_WEB_PACK) {
            val storage = pickPackFor
            pickPackFor = null
            val id = data?.getStringExtra(PackCatalogActivity.EXTRA_PACK_ID)
            if (resultCode == RESULT_OK && id != null) storage?.packId = id
        }
    }

    private fun handlePackPicked(resultCode: Int, data: Intent?) {
        val storage = pickPackFor
        pickPackFor = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val tempZip = File(cacheDir, "import.zip")
        contentResolver.openInputStream(uri)?.use { input ->
            tempZip.outputStream().use { output -> input.copyTo(output) }
        }
        val result = runCatching { PackInstaller.install(tempZip, packsDir) }
        tempZip.delete()

        result.onSuccess { manifest ->
            storage?.packId = manifest.id
            Toast.makeText(this, manifest.name, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(this, getString(R.string.pack_import_failed, error.message), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_PICK_PACK = 2
        private const val REQUEST_WEB_PACK = 3
        private const val KEY_LAST_SELECTED = "last_selected"
        private const val JUMP = 5
        private const val BLINK_MS = 530L
        private const val COMPACT_WIDTH_DP = 700
    }
}
