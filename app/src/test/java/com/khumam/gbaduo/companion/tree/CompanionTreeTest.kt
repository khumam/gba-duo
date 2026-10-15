package com.khumam.gbaduo.companion.tree

import androidx.compose.ui.graphics.ImageBitmap
import com.khumam.gbaduo.companion.Action
import com.khumam.gbaduo.companion.LayoutParser
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.PseudoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The companion screen's model: bindings, CSS resolution per node, lists, tabs,
 * bars and images. Rendering itself is covered by PackScreenshotTest.
 */
class CompanionTreeTest {

    private val defaultCss = CssParser.parse(File("src/main/assets/companion/default.css").readText())

    private fun tree(layout: String, css: String = "", images: (String) -> ImageBitmap? = { null }): CompanionTree {
        val sheet = CssParser.parse(css)
        assertTrue("css warnings: ${sheet.warnings}", sheet.warnings.isEmpty())
        return CompanionTree(LayoutParser.parse(layout), listOf(defaultCss), listOf(sheet), images)
    }

    private fun CompanionNode.child(vararg path: Int): CompanionNode = path.fold(this) { n, i -> n.children[i] }

    @Test
    fun legacyLayoutGetsItsOldDefaults() {
        val t = tree(
            """{"type":"column","gap":10,"children":[
                 {"type":"image","src":"asset:x.png"},
                 {"type":"box","padding":6,"background":"#80FF0000","cornerRadius":4,"child":{"type":"spacer","size":10}},
                 {"type":"text","text":"hi","size":18,"bold":true}
               ]}""",
        )
        val root = t.root.computed!!
        assertEquals("100%", root.raw("width"))
        assertEquals("column", root.keyword("flex-direction"))
        assertEquals("10px", root.raw("row-gap"))
        val image = t.root.child(0).computed!!
        assertEquals("32px", image.raw("width"))
        assertEquals("pixelated", image.keyword("image-rendering"))
        val box = t.root.child(1).computed!!
        assertEquals("stack", box.keyword("display"))
        assertEquals("flex-start", box.keyword("align-self"))
        assertEquals(0x80FF0000.toInt(), box.backgroundColor) // #AARRGGBB kept its Android meaning
        assertEquals(6f, box.padding("left"))
        val text = t.root.child(2).computed!!
        assertEquals(18f, text.fontSizePx)
        assertEquals(700, text.fontWeight)
        assertEquals(0xFFFFFFFF.toInt(), text.color)
    }

    @Test
    fun classIfBindStyleAndNthChildResolvePerItem() {
        val t = tree(
            """{"type":"list","class":"party","items":"${'$'}party","template":
                 {"type":"box","class":"mon","classIf":{"fainted":"${'$'}fainted"},"bindStyle":{"--type":"{color}"},"children":[
                   {"type":"text","class":"name","text":"{name}"}]}}""",
            css = ".mon { background: var(--type, #333) } .mon.fainted { opacity: .4 } .mon:nth-child(odd) .name { color: #ffd700 }",
        )
        fun party(vararg fainted: Boolean) = mapOf("party" to fainted.mapIndexed { i, f -> mapOf("name" to "m$i", "fainted" to f, "color" to "#f8d030") })
        t.update(party(false, true, false))
        val items = t.root.children.filter { it.kind == CompanionNode.Kind.WIDGET }
        assertEquals(3, items.size)
        assertEquals(1f, items[0].computed!!.opacity)
        assertEquals(0.4f, items[1].computed!!.opacity)
        assertEquals(0xFFF8D030.toInt(), items[0].computed!!.backgroundColor)
        assertEquals("m0", items[0].child(0).text)
        assertEquals(0xFFFFD700.toInt(), items[0].child(0).computed!!.color) // odd item
        assertEquals(0xFFFFFFFF.toInt(), items[1].child(0).computed!!.color)

        t.update(party(true, false, false))
        assertEquals(0.4f, items[0].computed!!.opacity)
        assertEquals(1f, items[1].computed!!.opacity)
    }

    @Test
    fun barFillUsesFractionsAndLabelInterpolates() {
        val t = tree("""{"type":"bar","value":"${'$'}frac","label":"{pct}%"}""")
        val fill = t.root.child(0)
        val label = t.root.child(1)
        assertEquals(CompanionNode.Kind.FILL, fill.kind)
        assertEquals("%", label.text) // placeholders empty before the first snapshot
        t.update(mapOf("frac" to 0.75, "pct" to 75))
        assertEquals("75.000%", fill.computed!!.raw("width"))
        assertEquals("75%", label.text)
        t.update(mapOf("frac" to 2.0, "pct" to 200))
        assertEquals("100.000%", fill.computed!!.raw("width"))
    }

    @Test
    fun nestedListsIterateTheItemsOwnList() {
        val t = tree("""{"type":"list","items":"${'$'}party","template":{"type":"list","items":"${'$'}moves","template":{"type":"text","text":"{@index}:{name}"}}}""")
        t.update(mapOf("party" to listOf(mapOf("moves" to listOf(mapOf("name" to "tackle"), mapOf("name" to "growl"))), mapOf("moves" to emptyList<Any>()))))
        val firstMoves = t.root.child(1)
        assertEquals(listOf("0:tackle", "1:growl"), firstMoves.children.drop(1).map { it.text })
        assertEquals(1, t.root.child(2).children.size) // only its empty label
    }

    @Test
    fun emptyLabelShowsOnlyWithoutItems() {
        val t = tree("""{"type":"list","items":"${'$'}bag","empty":"Bag is empty","template":{"type":"text","text":"{name}"}}""")
        val empty = t.root.child(0)
        t.update(mapOf("bag" to emptyList<Any>()))
        assertFalse(empty.hidden)
        assertEquals("Bag is empty", empty.text)
        t.update(mapOf("bag" to listOf(mapOf("name" to "Potion"))))
        assertTrue(empty.hidden)
    }

    @Test
    fun tabsSwitchActiveStateAndPanels() {
        val t = tree(
            """{"type":"tabs","tabs":[{"title":"A","child":{"type":"text","text":"a"}},{"title":"B","child":{"type":"text","text":"b"}}]}""",
            css = "tab:active { color: #ff0000 }",
        )
        val bar = t.root.child(0)
        val content = t.root.child(1)
        assertEquals(0xFFFF0000.toInt(), bar.child(0).computed!!.color)
        assertEquals(0xFF888888.toInt(), bar.child(1).computed!!.color)
        assertFalse(content.child(0).hidden)
        assertTrue(content.child(1).hidden)
        bar.child(1).onClick!!.invoke()
        assertEquals(0xFFFF0000.toInt(), bar.child(1).computed!!.color)
        assertTrue(PseudoState.ACTIVE in bar.child(1).style.states)
        assertTrue(content.child(0).hidden)
        assertFalse(content.child(1).hidden)
    }

    @Test
    fun pressedStateRestyles() {
        val t = tree("""{"type":"tabs","tabs":[{"title":"A","child":{"type":"spacer"}}]}""", css = "tab:pressed { opacity: .5 }")
        val tab = t.root.child(0, 0)
        t.setPressed(tab, true)
        assertEquals(0.5f, tab.computed!!.opacity)
        t.setPressed(tab, false)
        assertEquals(1f, tab.computed!!.opacity)
    }

    @Test
    fun visibleIfHidesWithoutRestyling() {
        val t = tree("""{"type":"text","text":"x","visibleIf":"${'$'}show"}""")
        t.update(mapOf("show" to false))
        assertTrue(t.root.hidden)
        t.update(mapOf("show" to 1))
        assertFalse(t.root.hidden)
    }

    @Test
    fun imagesLoadByKeyAndSpritesRetryUntilDecoded() {
        val requested = mutableListOf<String>()
        var spriteReady = false
        val fake = ImageBitmap(1, 1)
        val t = tree(
            """{"type":"column","children":[{"type":"image","src":"asset:icon.png"},{"type":"image","src":"gfx:0000abcd"},{"type":"image","src":"${'$'}icon"}]}""",
            images = { key ->
                requested += key
                if (key.startsWith("gfx:") && !spriteReady) null else fake
            },
        )
        assertEquals(fake, t.root.child(0).image)
        t.update(mapOf("icon" to "asset:a.png"))
        assertNull(t.root.child(1).image) // the script hasn't decoded it yet
        spriteReady = true
        t.update(mapOf("icon" to "asset:a.png"))
        assertEquals(fake, t.root.child(1).image)
        val before = requested.size
        t.update(mapOf("icon" to "asset:a.png"))
        assertEquals(before, requested.size) // loaded images aren't requested again
        t.update(mapOf("icon" to "asset:b.png"))
        assertEquals("asset:b.png", requested.last())
    }

    @Test
    fun viewportUnitsFollowTheScreenSize() {
        val t = tree("""{"type":"box"}""", css = "box { width: 50vw }")
        t.setViewport(400f, 300f)
        assertEquals(200f, (t.root.computed!!.length("width") as com.khumam.gbaduo.companion.style.Length.Px).px, 0.01f)
        t.setViewport(800f, 300f)
        assertEquals(400f, (t.root.computed!!.length("width") as com.khumam.gbaduo.companion.style.Length.Px).px, 0.01f)
    }

    @Test
    fun unchangedStylesKeepTheSameObject() {
        // Equal styles aren't re-assigned, so Compose doesn't recompose those nodes.
        val t = tree("""{"type":"column","children":[{"type":"text","text":"{a}"},{"type":"bar","value":"${'$'}v"}]}""")
        val textStyle = t.root.child(0).computed
        t.update(mapOf("a" to "x", "v" to 0.5))
        assertTrue(textStyle === t.root.child(0).computed)
    }

    // ---- onClick, pages and UI state -----------------------------------------------

    private val partyWithDetail = """{"type":"pages","pages":[
        {"name":"party","child":{"type":"list","items":"${'$'}party","template":
          {"type":"row","class":"mon","onClick":[{"set":{"selected":"{@index}","name":"{name}"}},{"navigate":"detail"}],
           "children":[{"type":"text","text":"{name}"}]}}},
        {"name":"detail","child":{"type":"column","children":[
          {"type":"button","text":"Back","onClick":"back"},
          {"type":"text","text":"{detail.name} #{ui.selected}"},
          {"type":"button","text":"Heal","onClick":{"call":"heal","args":["{ui.selected}","{detail.name}"]}}
        ]}}
      ]}"""

    @Test
    fun tappingAListItemNavigatesToItsDetailPage() {
        val t = tree(partyWithDetail)
        val uiChanges = mutableListOf<Map<String, Any?>>()
        t.onUiChange = { uiChanges += it }
        t.update(mapOf("party" to listOf(mapOf("name" to "Bulba"), mapOf("name" to "Pika")), "detail" to mapOf("name" to "?")))
        val partyPage = t.root.child(0)
        val detailPage = t.root.child(1)
        assertEquals(mapOf<String, Any?>("page" to "party"), t.ui)
        assertFalse(partyPage.hidden)
        assertTrue(detailPage.hidden)

        partyPage.children[2].onClick!!.invoke() // "Pika" (children[0] is the list's empty part)
        assertEquals(mapOf<String, Any?>("page" to "detail", "selected" to 1L, "name" to "Pika"), t.ui)
        assertEquals(listOf(t.ui), uiChanges)
        assertTrue(partyPage.hidden)
        assertFalse(detailPage.hidden)
        assertEquals("? #1", detailPage.child(1).text) // bindings re-ran with the new ui at once

        t.update(mapOf("party" to emptyList<Any>(), "detail" to mapOf("name" to "Pika")))
        assertEquals("Pika #1", detailPage.child(1).text)

        detailPage.child(0).onClick!!.invoke() // Back
        assertEquals("party", t.ui["page"])
        assertFalse(partyPage.hidden)
        assertEquals(1L, t.ui["selected"]) // other ui state stays
    }

    @Test
    fun aCallRunsAfterTheUiChangeFromTheSameTap() {
        val t = tree(
            """{"type":"list","items":"${'$'}party","template":{"type":"box",
                 "onClick":[{"call":"open","args":["{@index}","{name}","Lv {level}",true]},{"set":{"selected":"{@index}"}}]}}""",
        )
        val events = mutableListOf<String>()
        t.onUiChange = { events += "ui ${it["selected"]}" }
        t.onCall = { name, args -> events += "call $name $args" }
        t.update(mapOf("party" to listOf(mapOf("name" to "A", "level" to 5L), mapOf("name" to "B", "level" to 7L))))
        t.root.children[2].onClick!!.invoke()
        assertEquals(listOf("ui 1", "call open [1, B, Lv 7, true]"), events)
    }

    @Test
    fun backWithoutHistoryReturnsToTheInitialPageAndHistoryNests() {
        val t = tree(
            """{"type":"pages","initial":"a","pages":[
                 {"name":"a","child":{"type":"spacer"}},{"name":"b","child":{"type":"spacer"}},{"name":"c","child":{"type":"spacer"}}]}""",
        )
        t.update(emptyMap<String, Any?>())
        t.perform(listOf(Action.Navigate("b", "page"), Action.Navigate("c", "page")))
        assertEquals("c", t.ui["page"])
        t.perform(listOf(Action.Back("page")))
        assertEquals("b", t.ui["page"])
        t.perform(listOf(Action.Back("page")))
        assertEquals("a", t.ui["page"])
        t.perform(listOf(Action.Set(mapOf("page" to "b"))))
        t.perform(listOf(Action.Back("page"))) // a plain `set` leaves no history: back to the initial page
        assertEquals("a", t.ui["page"])
    }

    @Test
    fun anUnknownPageShowsTheInitialOneAndNoChangeReportsNothing() {
        val t = tree("""{"type":"pages","pages":[{"name":"a","child":{"type":"spacer"}},{"name":"b","child":{"type":"spacer"}}]}""")
        var changes = 0
        t.onUiChange = { changes++ }
        t.update(emptyMap<String, Any?>())
        t.perform(listOf(Action.Set(mapOf("page" to "typo"))))
        assertFalse(t.root.child(0).hidden)
        assertTrue(t.root.child(1).hidden)
        t.perform(listOf(Action.Set(mapOf("page" to "typo"))))
        assertEquals(1, changes)
    }

    @Test
    fun scriptResultsDriveNavigationWithoutAContext() {
        val t = tree(partyWithDetail)
        t.update(mapOf("party" to emptyList<Any>()))
        // What `return { set = { selected = 0 }, navigate = "detail" }` from actions.* becomes.
        t.perform(listOf(Action.Set(mapOf("selected" to 0L)), Action.Navigate("detail", "page")))
        assertEquals("detail", t.ui["page"])
        assertFalse(t.root.child(1).hidden)
        assertEquals(" #0", t.root.child(1, 1).text)
    }

    @Test
    fun buttonsGetDefaultStylesALabelAndPressedState() {
        val t = tree("""{"type":"button","text":"Go","onClick":{"navigate":"x"}}""")
        val button = t.root
        assertEquals("row", button.computed!!.keyword("flex-direction"))
        assertEquals("Go", button.child(0).text)
        assertTrue("label" in button.child(0).style.staticClasses)
        t.setPressed(button, true)
        assertEquals(0.7f, button.computed!!.opacity, 0.001f)
    }
}
