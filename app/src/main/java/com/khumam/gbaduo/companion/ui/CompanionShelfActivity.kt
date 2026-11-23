package com.khumam.gbaduo.companion.ui

import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import com.khumam.gbaduo.library.ui.PixelButton
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.khumam.gbaduo.R
import com.khumam.gbaduo.achievements.HardcoreMode
import com.khumam.gbaduo.companion.CatalogInfo
import com.khumam.gbaduo.companion.CompanionCard
import com.khumam.gbaduo.companion.CompanionPackSource
import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.companion.PackDownloader
import com.khumam.gbaduo.companion.PackInstaller
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.BuildConfig
import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.library.artwork.UrlHttp
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable
import com.khumam.gbaduo.settings.PixelPage
import com.khumam.gbaduo.storage.GameStorage
import java.io.File
import java.util.concurrent.Executors

/**
 * The second screen while the library is up. By default it shows the clock ([CompanionClockView]); the Companion pack
 * button brings up the packs for the selected game as a carousel: packs on the device, packs on the web, and a last
 * card to import one from a file. A applies the focused card straight away. B goes back to the clock, and so does
 * leaving the library for settings; a game starting closes the screen.
 */
class CompanionShelfActivity : PixelPage() {

    private val generation = LoadGeneration()
    private val io = Executors.newSingleThreadExecutor()
    private val imagesIo = Executors.newFixedThreadPool(2)
    /** Pack downloads, apart from [io] so moving between games never waits on one. Not cut short when the shelf closes. */
    private val downloads = Executors.newFixedThreadPool(2)
    /** Per busy key, the games waiting on that download to be given its pack. Guarded by itself. */
    private val waiting = mutableMapOf<String, MutableList<GameStorage>>()
    private val images by lazy { OverviewImages(OverviewCache(UrlHttp(), File(cacheDir, "overview"))) }
    private lateinit var source: CompanionPackSource
    private lateinit var row: LinearLayout
    private lateinit var carousel: HorizontalScrollView
    private lateinit var detail: LinearLayout
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailBody: TextView
    private lateinit var detailScroll: ScrollView
    private var detailShown: CompanionCard? = null
    /** The cards on screen, to show a download on the right one, and the downloads or imports under way. */
    private val cardViews = mutableMapOf<CompanionCard, View>()
    private val busy = mutableSetOf<String>()
    /** The cards now on screen: an update with the same cards leaves the screen alone instead of rebuilding it. */
    private var shown: List<CompanionCard>? = null
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var reloadButton: PixelButton
    private lateinit var strip: LinearLayout
    private var spin: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var packCount = 0
    private var storage: GameStorage? = null
    private var gameCode = ""
    private var crc = 0L
    private lateinit var clock: CompanionClockView
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controllerHere = intent.getBooleanExtra(EXTRA_FOCUS, false)
        val packsDir = File(filesDir, "packs")
        source = CompanionPackSource(PackStore(packsDir), PackDownloader(UrlHttp(), packsDir, cacheDir), packsDir, BuildConfig.VERSION_NAME, cacheFile = File(cacheDir, "catalog.cache"))
        page(getString(R.string.shelf_title), listOf("◀ ▶" to getString(R.string.shelf_hint_choose), "▲ ▼" to getString(R.string.shelf_hint_read), "A" to getString(R.string.shelf_hint_apply), "X" to getString(R.string.shelf_hint_reload)))
        // The clock covers the whole page until the Companion pack button asks for the packs.
        clock = CompanionClockView(this)
        addContentView(clock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (intent.getBooleanExtra(EXTRA_FOCUS, false)) clock.visibility = View.GONE
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        carousel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }
        // The status strip: what state the list is in, and the one button that refreshes it. It is a tool for the list,
        // so it sits beside the carousel and not in it.
        statusDot = View(this).apply { background = ColorDrawable(Pixel.YELLOW) }
        statusText = TextView(this).apply {
            typeface = Fonts.display(this@CompanionShelfActivity)
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Pixel.ON_INK_SOFT)
            isSingleLine = true
        }
        reloadButton = button("", Pixel.CREAM) { reloadAll() }.apply {
            isFocusable = false // the controller reloads with X; this is for touch
            contentDescription = getString(R.string.shelf_hint_reload)
            icon = getDrawable(R.drawable.ic_reload)
            iconSizePx = dp(20)
            minHeight = dp(36)
            setMinWidth(dp(44))
            minimumWidth = dp(44)
        }
        strip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reloadButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(strip, spaced(0))
        content.addView(carousel, spaced(10))
        // Plain text on the page, full width. Only this part scrolls; the carousel above it stays put.
        // Nothing in here overlaps, so a fade can dim each piece directly. The default is a full-size offscreen layer
        // every frame, which cost ~16ms of drawing per focus change on the Thor.
        detail = object : LinearLayout(this) {
            override fun hasOverlappingRendering() = false
        }.apply { orientation = LinearLayout.VERTICAL }
        detailTitle = titleText("").apply { textSize = 20f; setTextColor(Pixel.YELLOW); maxLines = 2 }
        detailBody = metaText("").apply { textSize = 15f; setLineSpacing(0f, 1.3f); setTextColor(Pixel.CREAM) }
        detailMeta = metaText("").apply {
            typeface = Fonts.display(this@CompanionShelfActivity)
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Pixel.ON_INK_SOFT)
        }
        detail.addView(detailTitle)
        detail.addView(detailBody, spaced(8))
        detail.addView(detailMeta, spaced(10))
        detailScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(detail)
        }
        content.addView(detailScroll, spaced(16).apply { height = dp(120) })
        // The detail takes whatever height the carousel leaves.
        (content.parent as View).addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val room = (bottom - top) - content.paddingTop - content.paddingBottom - strip.height - carousel.height - dp(10) - dp(16)
            val height = maxOf(room, dp(60))
            if (carousel.height > 0 && strip.height > 0 && detailScroll.layoutParams.height != height) {
                detailScroll.layoutParams.height = height
                detailScroll.requestLayout()
            }
        }
        load(intent)
        alive = true
        // Opened by hovering, not by the Companion pack button: the launch moved the controller here, take it back.
        if (!intent.getBooleanExtra(EXTRA_FOCUS, false)) row.post { returnToLibrary() }
    }

    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET) showClock() else close()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, closer, IntentFilter(ACTION_CLOSE).apply { addAction(ACTION_RESET) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Launched just as the library left for a game: its close was sent before this could hear it.
        if (gameStarting) close()
    }

    /** Goes at once as far as the library can tell, so its next selection launches a new shelf instead of updating this one. */
    private fun close() {
        alive = false
        closing = true
        finish()
    }

    /** The clock covers the packs again and the controller goes back to the library. */
    private fun showClock() {
        controllerHere = false
        clock.refresh()
        clock.visibility = View.VISIBLE
    }

    private fun showPacks() {
        clock.visibility = View.GONE
    }

    /** The page's Back button finishes it; here that means back to the clock, since the clock is what the screen is for. */
    override fun finish() {
        if (closing) { super.finish(); return }
        returnToLibrary()
        showClock()
    }

    override fun onStop() {
        unregisterReceiver(closer)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
        // A hover relaunch (the library thought no shelf was up) moved the controller here too: hand it back.
        if (!intent.getBooleanExtra(EXTRA_FOCUS, false) && !controllerHere) row.post { returnToLibrary() }
    }

    override fun onDestroy() {
        alive = false
        spin?.cancel()
        pulse?.cancel()
        io.shutdownNow()
        imagesIo.shutdownNow()
        downloads.shutdown() // lets a download under way finish and assign its pack
        super.onDestroy()
    }

    /** B hands the controller back to the library on the main screen; the shelf stays up, following it. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            // Up and down read the details; left and right choose the card.
            if (event.action == KeyEvent.ACTION_DOWN) detailScroll.smoothScrollBy(0, dp(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -48 else 48))
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) reloadAll()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { returnToLibrary(); showClock() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Whether the controller is on this screen. Opened by hovering a game it is not: the library keeps it, and only the
     * Companion pack button brings it here. The window itself is always focusable: if input focus lands on this screen
     * (a touch, or the file picker opening here) a window that refused focus left keys with nowhere to go, and Android
     * killed the app for not responding.
     */
    private var controllerHere = false
        set(value) {
            field = value
            applyFocusMode()
        }

    /**
     * While the controller is with the library, nothing on this screen may take focus: the window is focusable (see
     * above) and would otherwise light up its first button for no reason. The controller's arrival lifts that.
     */
    private fun applyFocusMode() {
        val root = window.decorView as ViewGroup
        if (controllerHere) {
            root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            currentFocus?.clearFocus()
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun returnToLibrary() {
        controllerHere = false
        startActivity(
            Intent(this, FocusBackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            ActivityOptions.makeBasic().setLaunchDisplayId(MAIN_DISPLAY).toBundle(),
        )
    }

    /** Fetches the catalog and every picture again, ignoring what was saved. */
    private fun reloadAll() {
        val game = storage ?: return
        source.refresh()
        images.invalidate()
        shown = null
        reload(game, focus = hasWindowFocus())
    }

    private fun load(intent: Intent) {
        if (!intent.hasExtra(EXTRA_GAME_CODE)) return // the clock alone: no game yet
        if (intent.getBooleanExtra(EXTRA_FOCUS, false)) showPacks()
        gameCode = intent.getStringExtra(EXTRA_GAME_CODE).orEmpty()
        crc = intent.getLongExtra(EXTRA_CRC, 0L)
        if (intent.getBooleanExtra(EXTRA_FOCUS, false)) controllerHere = true
        val platform = Platform.byId(intent.getStringExtra(EXTRA_PLATFORM_ID)) ?: Platform.GBA
        val game = GameStorage.forGame(filesDir, platform, intent.getStringExtra(EXTRA_TITLE).orEmpty(), gameCode, crc)
        storage = game
        reload(game, focus = intent.getBooleanExtra(EXTRA_FOCUS, false))
    }

    private fun reload(game: GameStorage, focus: Boolean) {
        val token = generation.next()
        // The spinner is for the first load and an explicit reload; moving between games keeps the old cards up until
        // the new ones are ready, so the screen doesn't flash.
        if (shown == null) {
            row.removeAllViews()
            cardViews.clear()
            row.addView(busyRow(getString(R.string.shelf_loading)))
            setStatus(getString(R.string.shelf_status_loading).uppercase(), Pixel.YELLOW, loading = true)
        }
        // Read now, on the main thread: by the time the worker runs, a newer selection may have changed them.
        val code = gameCode
        val checksum = crc
        io.execute {
            val cards = source.cards(code, checksum, game.packId)
            val info = source.info()
            runOnUiThread {
                if (isDestroyed || !generation.isCurrent(token)) return@runOnUiThread
                showStatus(cards, info)
                render(cards, focus)
            }
        }
    }

    /** The strip's line: how many packs and how fresh the list is, or that the web could not be reached. */
    private fun showStatus(cards: List<CompanionCard>, info: CatalogInfo) {
        packCount = cards.count { it !is CompanionCard.Import }
        if (info.offline) {
            val text = getString(if (info.updatedAt != null) R.string.shelf_status_offline_saved else R.string.shelf_status_offline_none)
            setStatus(text.uppercase(), Pixel.PINK, loading = false)
            return
        }
        val count = if (packCount == 0) getString(R.string.shelf_status_none)
        else resources.getQuantityString(R.plurals.shelf_status_packs, packCount, packCount)
        val age = info.updatedAt?.let { ageText(AgeLabel.of(System.currentTimeMillis() - it)) }
        setStatus(listOfNotNull(count, age).joinToString(" · ").uppercase(), Pixel.MINT, loading = false)
    }

    private fun ageText(age: AgeLabel) = when (age) {
        AgeLabel.JustNow -> getString(R.string.shelf_age_now)
        is AgeLabel.Minutes -> getString(R.string.shelf_age_min, age.count)
        is AgeLabel.Hours -> getString(R.string.shelf_age_hour, age.count)
        is AgeLabel.Days -> getString(R.string.shelf_age_day, age.count)
    }

    /** Sets the strip's text and dot colour. While [loading] the dot pulses and the reload icon turns. */
    private fun setStatus(text: String, color: Int, loading: Boolean) {
        statusText.text = text
        (statusDot.background as ColorDrawable).color = color
        statusDot.invalidate()
        if (loading) {
            if (spin == null) spin = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = SPIN_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { reloadButton.iconRotation = it.animatedValue as Float }
                start()
            }
            if (pulse == null) pulse = ValueAnimator.ofFloat(1f, 0.25f).apply {
                duration = PULSE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { statusDot.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            spin?.cancel(); spin = null
            pulse?.cancel(); pulse = null
            reloadButton.iconRotation = 0f
            statusDot.alpha = 1f
        }
    }

    private fun render(cards: List<CompanionCard>, focus: Boolean) {
        val targetCard = cards.firstOrNull { it is CompanionCard.Installed && it.assigned } ?: cards.firstOrNull()
        if (cards == shown) {
            syncBusyCovers()
            if (focus) targetCard?.let { cardViews[it] }?.let(::focusFirst)
            return
        }
        shown = cards
        row.removeAllViews()
        cardViews.clear()
        for (card in cards) {
            val view = cardView(card)
            cardViews[card] = view
            row.addView(view, LinearLayout.LayoutParams(dp(250), dp(136)).apply { marginEnd = dp(18) })
        }
        showDetail(targetCard, animate = false)
        if (focus) targetCard?.let { cardViews[it] }?.let(::focusFirst)
    }

    private fun overviewOf(card: CompanionCard): String? = when (card) {
        is CompanionCard.Installed -> card.overview
        is CompanionCard.Web -> card.overview
        CompanionCard.Import -> null
    }

    private fun nameOf(card: CompanionCard): String = when (card) {
        is CompanionCard.Installed -> card.pack.name
        is CompanionCard.Web -> card.pack.title
        CompanionCard.Import -> getString(R.string.shelf_import)
    }

    /** A carousel card is only the pack's overview picture; the words live in the panel below, for the focused card. */
    private fun cardView(card: CompanionCard): View {
        val isImport = card is CompanionCard.Import
        val box = PixelBoxDrawable(this, Pixel.LAVENDER, shadowDp = 6f, shadowColor = SHADE, focusedFill = Pixel.YELLOW)
        val view = LinearLayout(this).apply {
            if (!isImport) {
                background = box
                setPadding(dp(8), dp(8), dp(8) + box.shadow.toInt(), dp(8) + box.shadow.toInt())
            }
            isClickable = true
            isFocusable = true
            // The ghost slot draws its own focus look; the system's overlay would show as a box around it.
            if (isImport) defaultFocusHighlightEnabled = false
            setOnClickListener { apply(card) }
            setOnFocusChangeListener { v, has ->
                v.translationX = if (has) -dp(2).toFloat() else 0f
                v.translationY = v.translationX
                if (has) showDetail(card, animate = true)
            }
        }
        val picture = if (isImport) ghostSlot() else packPicture(card)
        picture.addView(busyOverlay(card), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            // Packs have their hard shadow in the card's padding; the ghost has none but lines up with the packs' faces.
            if (isImport) setMargins(0, 0, box.shadow.toInt(), box.shadow.toInt())
        })
        return view
    }

    /** The picture frame of a pack card: dark, its name until the picture lands, the "in use" tag on top. */
    private fun packPicture(card: CompanionCard): FrameLayout {
        val picture = FrameLayout(this).apply { setBackgroundColor(Pixel.INK) }
        // Shown while the picture downloads, and kept when a pack has none or the download fails.
        picture.addView(TextView(this).apply {
            text = nameOf(card).uppercase()
            typeface = Fonts.display(this@CompanionShelfActivity, bold = true)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(Pixel.ON_INK_SOFT)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overviewOf(card)?.let { addOverview(picture, it) }
        if (card is CompanionCard.Installed && card.assigned) picture.addView(inUseLabel(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(8), dp(8), 0, 0) })
        return picture
    }

    /** The end of the carousel: an empty dashed slot with a line "+", that lights up yellow when focused. */
    private fun ghostSlot(): FrameLayout {
        // Colours follow the card's focus through the parent's state.
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Pixel.YELLOW, Pixel.ON_INK_SOFT),
        )
        val slot = FrameLayout(this).apply {
            background = GhostSlotDrawable(this@CompanionShelfActivity)
            isDuplicateParentStateEnabled = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isDuplicateParentStateEnabled = true
        }
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = tint
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        column.addView(TextView(this).apply {
            text = getString(R.string.shelf_add_pack).uppercase()
            typeface = Fonts.display(this@CompanionShelfActivity, bold = true)
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(tint)
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        slot.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return slot
    }

    /** A yellow tag on the picture of the pack this game uses. */
    private fun inUseLabel() = TextView(this).apply {
        text = getString(R.string.shelf_active).uppercase()
        typeface = Fonts.display(this@CompanionShelfActivity, bold = true)
        textSize = 10f
        letterSpacing = 0.06f
        setTextColor(Pixel.INK)
        background = PixelBoxDrawable(this@CompanionShelfActivity, Pixel.YELLOW, shadowDp = 2f, shadowColor = SHADE, borderDp = 2f)
        setPadding(dp(8), dp(4), dp(8) + dp(2), dp(4) + dp(2))
    }

    /** A dimmed cover with a spinner and a word, shown on a card while its pack downloads or is imported. */
    private fun busyOverlay(card: CompanionCard): View {
        val label = getString(if (card is CompanionCard.Import) R.string.shelf_importing else R.string.shelf_downloading)
        return FrameLayout(this).apply {
            tag = BUSY_TAG
            setBackgroundColor(0xCC120C26.toInt())
            addView(busyRow(label), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            visibility = if (busyKey(card)?.let { it in busy } == true) View.VISIBLE else View.GONE
        }
    }

    private fun busyKey(card: CompanionCard): String? = when (card) {
        is CompanionCard.Web -> "web:${card.pack.id}"
        CompanionCard.Import -> "import"
        else -> null
    }

    /** Every cover is shown exactly while its card is downloading or importing, whether or not the cards were rebuilt. */
    private fun syncBusyCovers() {
        for ((card, view) in cardViews) {
            val cover = view.findViewWithTag<View>(BUSY_TAG) ?: continue
            val on = busyKey(card)?.let { it in busy } == true
            if (!on && cover.visibility == View.VISIBLE) {
                cover.animate().cancel()
                cover.visibility = View.GONE
            }
        }
    }

    /** Fades the download cover in or out on [card]'s picture. */
    private fun showBusy(card: CompanionCard, on: Boolean) {
        val cover = cardViews[card]?.findViewWithTag<View>(BUSY_TAG) ?: return
        cover.animate().cancel()
        if (on) {
            cover.alpha = 0f
            cover.visibility = View.VISIBLE
            cover.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        } else {
            cover.animate().alpha(0f).setDuration(FADE_IN_MS).withEndAction { cover.visibility = View.GONE }.start()
        }
    }

    /** The pack's overview picture over the card's placeholder; it fills in when the download lands. */
    private fun addOverview(picture: FrameLayout, url: String) {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        picture.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = dp(PICTURE_WIDTH_DP)
        val height = dp(PICTURE_HEIGHT_DP)
        imagesIo.execute {
            val bitmap = images.load(url, width, height) ?: return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                image.setImageBitmap(bitmap)
            }
        }
    }

    /** Fills the panel below the carousel for [card]; moving focus fades the old text out and the new in. */
    private fun showDetail(card: CompanionCard?, animate: Boolean) {
        if (card == null || card == detailShown) return
        detailShown = card
        detail.animate().cancel()
        // The new text is there at once and fades up from dim; waiting for the old text to fade out first felt slow.
        fillDetail(card)
        if (!animate) {
            detail.alpha = 1f
            return
        }
        detail.alpha = FADE_FROM
        detail.animate().alpha(1f).setDuration(FADE_IN_MS).start()
    }

    /** NAME, then the description, then the rest on one line: author | version | badges. */
    private fun fillDetail(card: CompanionCard) {
        val facts = mutableListOf<String>()
        val badges = mutableListOf<String>()
        var body = ""
        when (card) {
            is CompanionCard.Installed -> {
                facts += card.pack.author
                facts += "v${card.pack.version}"
                body = card.description.orEmpty()
                if (card.assigned) badges += getString(R.string.shelf_active)
                if (card.otherRegion) badges += getString(R.string.pack_other_region)
                if (card.pack.canWriteMemory) badges += getString(R.string.pack_modifies_game)
            }
            is CompanionCard.Web -> {
                facts += card.pack.author
                facts += "v${card.pack.version}"
                body = card.pack.description
                badges += getString(if (card.update) R.string.shelf_update else R.string.shelf_download)
                if (card.otherRegion) badges += getString(R.string.pack_other_region)
                if (card.pack.canModifyGame) badges += getString(R.string.pack_modifies_game)
            }
            CompanionCard.Import -> body = getString(if (packCount == 0) R.string.shelf_empty_hint else R.string.shelf_import_hint)
        }
        detailTitle.text = nameOf(card).uppercase()
        detailBody.text = body
        detailBody.visibility = if (body.isBlank()) View.GONE else View.VISIBLE
        val line = SpannableStringBuilder(facts.joinToString("  |  ").uppercase())
        if (badges.isNotEmpty()) {
            if (line.isNotEmpty()) line.append("  |  ")
            val start = line.length
            line.append(badges.joinToString("  |  ").uppercase())
            line.setSpan(ForegroundColorSpan(Pixel.YELLOW), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        detailMeta.text = line
        detailMeta.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        detailScroll.scrollTo(0, 0)
    }

    private fun titleText(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        typeface = Fonts.display(this@CompanionShelfActivity, bold = true)
        textSize = 15f
        letterSpacing = 0.04f
        setTextColor(Pixel.INK)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun metaText(text: String) = TextView(this).apply {
        this.text = text
        typeface = Fonts.body(this@CompanionShelfActivity, 500)
        textSize = 12f
        setTextColor(Pixel.INK_SOFT)
    }

    // ---- applying ---------------------------------------------------------------------

    private fun apply(card: CompanionCard) {
        val region = when (card) {
            is CompanionCard.Installed -> if (card.otherRegion) card.pack.gameCodes to card.pack.canWriteMemory else null
            is CompanionCard.Web -> if (card.otherRegion) card.pack.games.map { it.gameCode } to card.pack.canModifyGame else null
            CompanionCard.Import -> null
        }
        if (region == null) return applyConfirmed(card)
        // Same checks as applyConfirmed makes, before the warning: no point warning about a pack that won't be used.
        if (region.second && HardcoreMode.enabled(this)) return toast(getString(R.string.pack_read_only_hardcore))
        val message = getString(R.string.pack_region_message, region.first.joinToString(", "), gameCode) +
            if (region.second) getString(R.string.pack_region_message_writes) else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.pack_region_title)
            .setMessage(message)
            .setPositiveButton(R.string.pack_region_continue) { _, _ -> applyConfirmed(card) }
            .setNegativeButton(R.string.pack_region_cancel, null)
            .show()
    }

    private fun applyConfirmed(card: CompanionCard) {
        val game = storage ?: return
        when (card) {
            is CompanionCard.Installed -> {
                if (card.pack.canWriteMemory && HardcoreMode.enabled(this)) return toast(getString(R.string.pack_read_only_hardcore))
                assign(game, source.resolve(card))
            }
            is CompanionCard.Web -> {
                if (card.pack.canModifyGame && HardcoreMode.enabled(this)) return toast(getString(R.string.pack_read_only_hardcore))
                val key = busyKey(card) ?: return
                // Already downloading (for this game or another one the pack covers): this game just joins the wait.
                val first = synchronized(waiting) {
                    val games = waiting[key]
                    if (games != null) {
                        if (games.none { it.dir == game.dir }) games += game
                        false
                    } else {
                        waiting[key] = mutableListOf(game)
                        true
                    }
                }
                if (!first) return
                busy.add(key)
                showBusy(card, true)
                downloads.execute { download(card, key) }
            }
            CompanionCard.Import -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"),
                REQUEST_PICK_PACK,
            )
        }
    }

    private fun assign(game: GameStorage, packId: String) {
        game.packId = packId
        reload(game, focus = true)
    }

    /**
     * On the download pool: installs [card]'s pack and gives it to every game that asked for it, even if the selection
     * has moved on or the shelf has closed meanwhile. The screen then catches up with whatever game it shows now.
     */
    private fun download(card: CompanionCard.Web, key: String) {
        val result = source.resolve(card)
        val games = synchronized(waiting) { waiting.remove(key) }.orEmpty()
        if (result is Fetch.Ok) games.forEach { it.packId = result.value.id }
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            busy.remove(key)
            showBusy(card, false)
            if (result is Fetch.Failed) toast(failureText(result.reason))
            // The installed pack replaces its web card (and shows "in use" if this is one of the games it went to).
            storage?.let { reload(it, focus = controllerHere) }
        }
    }

    private fun failureText(reason: Fetch.Reason) = getString(
        when (reason) {
            Fetch.Reason.OFFLINE -> R.string.pack_web_offline
            Fetch.Reason.TOO_BIG -> R.string.pack_web_too_big
            Fetch.Reason.BAD_CHECKSUM -> R.string.pack_web_bad_checksum
            Fetch.Reason.UNSUPPORTED_CATALOG -> R.string.pack_web_unsupported
            Fetch.Reason.DOWNLOAD_FAILED, Fetch.Reason.INSTALL_FAILED -> R.string.pack_web_download_failed
        },
    )

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // The file picker opened on this screen and took the controller with it; if the shelf wasn't opened with the
        // button, hand it back to the library.
        if (!controllerHere) returnToLibrary()
        if (requestCode != REQUEST_PICK_PACK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val game = storage ?: return
        if (!busy.add("import")) return
        showBusy(CompanionCard.Import, true)
        io.execute {
            val zip = File(cacheDir, "import.zip")
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input -> zip.outputStream().use { input.copyTo(it) } }
                source.importZip(zip)
            }
            zip.delete()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy.remove("import")
                // Hidden whatever happens next: a re-import of a pack already in use gives the same cards, and then
                // nothing rebuilds the screen to take the cover away.
                showBusy(CompanionCard.Import, false)
                result.onSuccess {
                    toast(getString(R.string.shelf_imported, it.name))
                    assign(game, it.id)
                }.onFailure {
                    toast(getString(R.string.pack_import_failed, it.message))
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        /** Sent by the library when it leaves for something other than a game (settings): back to the clock. */
        const val ACTION_RESET = "com.khumam.gbaduo.SHELF_RESET"

        /** Set by the library when a game is starting: the screen closes instead of going back to the clock. */
        @Volatile var gameStarting = false

        /** Whether a shelf is up in this process. */
        @Volatile var alive = false
            private set

        /** Set by the library while it is on screen; a shelf starting without it closes. */
        @Volatile var libraryShown = false

        /** Sent by the library when a game starts. */
        const val ACTION_CLOSE = "com.khumam.gbaduo.SHELF_CLOSE"
        const val EXTRA_PLATFORM_ID = "platform_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_GAME_CODE = "game_code"
        const val EXTRA_CRC = "crc"
        const val EXTRA_FOCUS = "focus"
        private const val REQUEST_PICK_PACK = 20
        private const val MAIN_DISPLAY = 0
        private const val SPIN_MS = 900L
        private const val PULSE_MS = 600L
        // A card's picture: the card (250 x 136dp) less its padding and its hard shadow, if the style has one.
        private val PICTURE_WIDTH_DP = 234 - if (Pixel.FLAT) 0 else 6
        private val PICTURE_HEIGHT_DP = 120 - if (Pixel.FLAT) 0 else 6
        private const val BUSY_TAG = "busy"
        private const val FADE_FROM = 0.2f
        private const val FADE_IN_MS = 140L
    }
}
