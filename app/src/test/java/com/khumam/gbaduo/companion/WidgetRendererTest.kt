package com.khumam.gbaduo.companion

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.khumam.gbaduo.companion.render.BoxParams
import com.khumam.gbaduo.companion.render.StyleDrawable
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.Length
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Renders real layouts with real Android views (Robolectric) and checks the
 * measured result: legacy layouts keep their look, CSS features apply, and the
 * renderer fixes (bar fractions, nested lists, placeholders, pixelated and
 * pack-confined images) hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WidgetRendererTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val defaultCss = CssParser.parse(File("src/main/assets/companion/default.css").readText())
    private val pack: File = Files.createTempDirectory("pack").toFile()

    private fun render(layout: String, css: String = "", snapshot: Map<String, Any?>? = null, width: Int = 1000, height: Int = 800): Pair<WidgetRenderer, View> {
        val sheet = CssParser.parse(css, baseDir = pack, packDir = pack)
        assertTrue("css warnings: ${sheet.warnings}", sheet.warnings.isEmpty())
        val renderer = WidgetRenderer(context, pack, listOf(defaultCss), listOf(sheet))
        val view = renderer.build(LayoutParser.parse(layout))
        snapshot?.let { renderer.update(it) }
        layout(view, width, height)
        return renderer to view
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper() // viewport size → posted restyle
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun View.child(vararg path: Int): View = path.fold(this) { v, i -> (v as ViewGroup).getChildAt(i) }
    private fun root(viewport: View) = viewport.child(0)
    private fun box(v: View) = listOf(v.left, v.top, v.width, v.height)

    @Test
    fun legacyLayoutKeepsItsLook() {
        val (_, viewport) = render(
            """{"type":"column","gap":10,"children":[
                 {"type":"image","src":"asset:none.png"},
                 {"type":"row","gap":4,"children":[{"type":"spacer","size":20},{"type":"spacer"}]},
                 {"type":"box","padding":6,"background":"#80FF0000","cornerRadius":4,"child":{"type":"spacer","size":10}},
                 {"type":"bar","value":"${'$'}hp","max":100}
               ]}""",
        )
        val root = root(viewport)
        assertEquals(listOf(0, 0, 1000, 800), box(root))            // :root fills the screen
        assertEquals(listOf(0, 0, 32, 32), box(root.child(0)))       // image default 32dp
        assertEquals(listOf(0, 42, 1000, 20), box(root.child(1)))    // gap 10, row stretched across the column
        assertEquals(listOf(0, 0, 20, 20), box(root.child(1, 0)))
        assertEquals(listOf(24, 0, 8, 8), box(root.child(1, 1)))     // gap 4, default spacer 8, not stretched in a row
        val legacyBox = root.child(2)
        assertEquals(listOf(0, 72, 22, 22), box(legacyBox))          // wraps its child (doesn't stretch), padding 6
        assertEquals(listOf(6, 6, 10, 10), box(legacyBox.child(0)))
        val paint = (legacyBox.background as StyleDrawable).spec
        assertEquals(0x80FF0000.toInt(), paint.backgroundColor)       // #AARRGGBB kept its Android meaning
        assertEquals(4f, paint.radii[0])
        assertEquals(listOf(0, 104, 1000, 12), box(root.child(3)))   // bar fills the width, 12dp high
    }

    @Test
    fun cssGridClassesBindingsAndNthChild() {
        val (renderer, viewport) = render(
            """{"type":"list","class":"party","items":"${'$'}party","template":
                 {"type":"box","class":"mon","classIf":{"fainted":"${'$'}fainted"},"bindStyle":{"--type":"{color}"},"children":[
                   {"type":"text","class":"name","text":"{name}"}
                 ]}}""",
            css = """
                :root { --gap: 8px }
                .party { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--gap); padding: 10px }
                .mon { height: 50px; background: var(--type, #333); border-radius: 6px }
                .mon.fainted { opacity: .4 }
                .mon:nth-child(odd) .name { color: #ffd700 }
                .name { text-transform: uppercase }
            """.trimIndent(),
            snapshot = mapOf("party" to listOf(
                mapOf("name" to "pika", "fainted" to false, "color" to "#f8d030"),
                mapOf("name" to "geo", "fainted" to true, "color" to "#b8a038"),
                mapOf("name" to "abra", "fainted" to false, "color" to "#f85888"),
                mapOf("name" to "mew", "fainted" to false, "color" to "#f85888"),
            )),
        )
        val list = root(viewport)
        val items = (1..4).map { list.child(it) } // child 0 is the hidden "empty" label
        assertEquals(View.GONE, list.child(0).visibility)
        // 1000 - 2*10 padding - 2*8 gaps = 964 / 3
        assertEquals(listOf(10, 10, 321, 50), box(items[0]))
        assertEquals(listOf(339, 10, 321, 50), box(items[1]))
        assertEquals(listOf(10, 68, 321, 50), box(items[3]))
        assertEquals(0.4f, items[1].alpha, 0.001f)
        assertEquals(1f, items[0].alpha, 0.001f)
        assertEquals(0xFFF8D030.toInt(), (items[0].background as StyleDrawable).spec.backgroundColor)
        val name0 = items[0].child(0) as TextView
        assertEquals("PIKA", name0.text.toString())
        assertEquals(0xFFFFD700.toInt(), name0.currentTextColor)         // odd item
        assertEquals(0xFFFFFFFF.toInt(), (items[1].child(0) as TextView).currentTextColor)

        // A class flip only restyles that item.
        renderer.update(mapOf("party" to listOf(
            mapOf("name" to "pika", "fainted" to true, "color" to "#f8d030"),
            mapOf("name" to "geo", "fainted" to false, "color" to "#b8a038"),
            mapOf("name" to "abra", "fainted" to false, "color" to "#f85888"),
            mapOf("name" to "mew", "fainted" to false, "color" to "#f85888"),
        )))
        assertEquals(0.4f, items[0].alpha, 0.001f)
        assertEquals(1f, items[1].alpha, 0.001f)
    }

    @Test
    fun barUsesFractionsAndShowsItsLabel() {
        val (renderer, viewport) = render(
            """{"type":"column","children":[{"type":"bar","value":"${'$'}frac","label":"{pct}%"}]}""",
            css = "bar { width: 200px; accent-color: #00ff00 }",
            snapshot = mapOf("frac" to 0.75, "pct" to 75),
        )
        val bar = root(viewport).child(0)
        val fill = bar.child(0)
        assertEquals(150, fill.width)                                    // 0.75 of max 1.0 (was 0 before)
        assertEquals(0xFF00FF00.toInt(), (fill.background as StyleDrawable).spec.backgroundColor)
        val label = bar.child(1) as TextView
        assertEquals("75%", label.text.toString())
        renderer.update(mapOf("frac" to 2.0, "pct" to 200))
        layout(viewport, 1000, 800)
        assertEquals(200, fill.width)                                    // clamped to full
    }

    @Test
    fun barFillWidthTransitionsInsteadOfJumping() {
        val (renderer, viewport) = render(
            """{"type":"bar","value":"${'$'}v","max":10}""",
            css = ":root { width: 100px } bar > fill { transition: width 1s linear }",
            snapshot = mapOf("v" to 0),
        )
        val fill = root(viewport).child(0)
        fun width() = (fill.layoutParams as BoxParams).box.width
        renderer.update(mapOf("v" to 10))
        // The transition starts from the shown width rather than jumping to the target...
        assertEquals(Length.Percent(0f), width())
        // ...and ends there. (Robolectric runs animators straight to their end, so no mid-frame check.)
        ShadowLooper.idleMainLooper(1100, TimeUnit.MILLISECONDS)
        assertEquals(Length.Percent(100f), width())
        layout(viewport, 1000, 800)
        assertEquals(100, fill.width)
    }

    @Test
    fun nestedListsIterateTheItemsOwnList() {
        val (_, viewport) = render(
            """{"type":"list","items":"${'$'}party","template":{"type":"list","items":"${'$'}moves","template":{"type":"text","text":"{@index}:{name}"}}}""",
            snapshot = mapOf("party" to listOf(mapOf("moves" to listOf(mapOf("name" to "tackle"), mapOf("name" to "growl"))), mapOf("moves" to emptyList<Any>()))),
        )
        val party = root(viewport)
        val firstMoves = party.child(1)
        assertEquals("0:tackle", (firstMoves.child(1) as TextView).text.toString())
        assertEquals("1:growl", (firstMoves.child(2) as TextView).text.toString())
        assertEquals(1, (party.child(2) as ViewGroup).childCount) // only its empty label
    }

    @Test
    fun placeholdersAreEmptyBeforeTheFirstSnapshot() {
        val (_, viewport) = render("""{"type":"text","text":"HP {hp}/{maxHp}"}""")
        assertEquals("HP /", (root(viewport) as TextView).text.toString())
    }

    @Test
    fun tabsSwitchActiveStateAndPanels() {
        val (_, viewport) = render(
            """{"type":"tabs","tabs":[{"title":"A","child":{"type":"text","text":"a"}},{"title":"B","child":{"type":"text","text":"b"}}]}""",
            css = "tab:active { color: #ff0000 }",
        )
        val tabs = root(viewport)
        val bar = tabs.child(0)
        val content = tabs.child(1)
        assertEquals(0xFFFF0000.toInt(), (bar.child(0) as TextView).currentTextColor)
        assertEquals(0xFF888888.toInt(), (bar.child(1) as TextView).currentTextColor)
        assertEquals(View.VISIBLE, content.child(0).visibility)
        assertEquals(View.GONE, content.child(1).visibility)
        bar.child(1).performClick()
        assertEquals(0xFFFF0000.toInt(), (bar.child(1) as TextView).currentTextColor)
        assertEquals(View.GONE, content.child(0).visibility)
        assertEquals(View.VISIBLE, content.child(1).visibility)
    }

    @Test
    fun imagesArePixelatedAndConfinedToThePack() {
        val png = File(pack, "assets/icon.png").apply { parentFile.mkdirs() }
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, png.outputStream())
        File(pack.parentFile, "outside.png").writeBytes(png.readBytes())
        val (_, viewport) = render(
            """{"type":"column","children":[{"type":"image","src":"asset:icon.png"},{"type":"image","src":"asset:../../outside.png"}]}""",
        )
        val ok = root(viewport).child(0) as ImageView
        val drawable = ok.drawable as BitmapDrawable
        assertFalse(drawable.paint.isFilterBitmap)                     // pixelated by default
        assertNull((root(viewport).child(1) as ImageView).drawable)     // ../ escape refused
    }

    @Test
    fun flexRowWithGrowAndAbsoluteBadge() {
        val (_, viewport) = render(
            """{"type":"row","children":[
                 {"type":"box","class":"a"},{"type":"box","class":"b"},
                 {"type":"box","class":"badge"}
               ]}""",
            css = """
                row { height: 100px; padding: 10px; align-items: center; position: relative }
                .a { width: 100px; height: 40px }
                .b { flex: 1; height: 20px; margin: 0 5px }
                .badge { position: absolute; right: 0; top: 0; width: 16px; height: 16px; border-radius: 50%; background: red; box-shadow: 0 2px 4px rgba(0,0,0,.5) }
            """.trimIndent(),
        )
        val row = root(viewport)
        assertEquals(listOf(10, 30, 100, 40), box(row.child(0)))
        assertEquals(listOf(115, 40, 870, 20), box(row.child(1)))     // 1000-20 padding -100 -10 margins
        assertEquals(listOf(984, 0, 16, 16), box(row.child(2)))
        assertNotNull((row.child(2).background as StyleDrawable).spec.shadow)
    }
}
