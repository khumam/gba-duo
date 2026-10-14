package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {

    @Test
    fun parsesText() {
        val widget = LayoutParser.parse("""{"type":"text","text":"Money: {money}","size":16}""")
        val text = widget as Widget.Text
        assertEquals("Money: {money}", text.text)
        assertEquals(16, text.size)
    }

    @Test
    fun parsesColumnWithChildren() {
        val widget = LayoutParser.parse(
            """{"type":"column","gap":8,"children":[{"type":"text","text":"a"},{"type":"spacer","size":4}]}""",
        )
        val column = widget as Widget.Column
        assertEquals(8, column.gap)
        assertEquals(2, column.children.size)
        assertTrue(column.children[0] is Widget.Text)
        assertTrue(column.children[1] is Widget.Spacer)
    }

    @Test
    fun parsesRowTabsBarImage() {
        val row = LayoutParser.parse("""{"type":"row","children":[]}""") as Widget.Row
        assertEquals(0, row.children.size)

        val tabs = LayoutParser.parse(
            """{"type":"tabs","tabs":[{"title":"Info","child":{"type":"text","text":"x"}}]}""",
        ) as Widget.Tabs
        assertEquals("Info", tabs.tabs[0].title)

        val bar = LayoutParser.parse(
            """{"type":"bar","value":"${'$'}hp","max":100,"height":12}""",
        ) as Widget.Bar
        assertEquals("hp", bar.valueKey)
        assertEquals(100.0, bar.max, 0.0)

        val image = LayoutParser.parse("""{"type":"image","src":"asset:icon.png"}""") as Widget.Image
        assertEquals("asset:icon.png", image.src)
    }

    @Test
    fun parsesVisibleIfAndDefaults() {
        val widget = LayoutParser.parse("""{"type":"spacer","size":4,"visibleIf":"${'$'}hasItem"}""")
        assertEquals("hasItem", widget.visibleIf)
    }

    @Test(expected = LayoutParser.InvalidLayoutException::class)
    fun rejectsUnknownType() {
        LayoutParser.parse("""{"type":"nonsense"}""")
    }

    @Test
    fun noVisibleIfMeansAlwaysVisible() {
        val widget = LayoutParser.parse("""{"type":"spacer","size":4}""")
        assertNull(widget.visibleIf)
    }

    @Test
    fun parsesList() {
        val widget = LayoutParser.parse(
            """{"type":"list","items":"${'$'}party","empty":"No Pokémon","template":{"type":"text","text":"{name}"}}""",
        ) as Widget.ItemList
        assertEquals("party", widget.itemsPath)
        assertEquals("No Pokémon", widget.emptyText)
        assertTrue(widget.template is Widget.Text)
    }

    @Test(expected = LayoutParser.InvalidLayoutException::class)
    fun listWithoutTemplateIsRejected() {
        LayoutParser.parse("""{"type":"list","items":"${'$'}party"}""")
    }

    @Test
    fun parsesBoxWithAllFields() {
        val widget = LayoutParser.parse(
            """{"type":"box","width":340,"height":160,"background":"#192134","cornerRadius":16,"borderColor":"#DC2626","borderWidth":2,"padding":12,"child":{"type":"text","text":"hi"}}""",
        ) as Widget.Box
        assertEquals(340, widget.width)
        assertEquals(160, widget.height)
        assertEquals("#192134", widget.background)
        assertEquals(16, widget.cornerRadius)
        assertEquals("#DC2626", widget.borderColor)
        assertEquals(2, widget.borderWidth)
        assertEquals(12, widget.padding)
        assertTrue(widget.child is Widget.Text)
    }

    @Test
    fun parsesBoxWithOnlyChildUsesDefaults() {
        val widget = LayoutParser.parse(
            """{"type":"box","child":{"type":"spacer","size":4}}""",
        ) as Widget.Box
        assertNull(widget.width)
        assertNull(widget.height)
        assertNull(widget.background)
        assertEquals(0, widget.cornerRadius)
        assertNull(widget.borderColor)
        assertEquals(0, widget.borderWidth)
        assertEquals(0, widget.padding)
    }

    @Test
    fun boxWithoutChildrenIsAllowed() {
        // e.g. a bar fill or divider drawn purely with CSS.
        val widget = LayoutParser.parse("""{"type":"box","background":"#000000"}""") as Widget.Box
        assertTrue(widget.children.isEmpty())
        assertNull(widget.child)
    }

    @Test
    fun boxChildrenFormIsNotLegacy() {
        val widget = LayoutParser.parse("""{"type":"box","children":[{"type":"text"},{"type":"spacer"}]}""") as Widget.Box
        assertEquals(2, widget.children.size)
        assertFalse(widget.legacyChild)
        assertTrue((LayoutParser.parse("""{"type":"box","child":{"type":"text"}}""") as Widget.Box).legacyChild)
    }

    @Test(expected = LayoutParser.InvalidLayoutException::class)
    fun tabsWithoutTabsAreRejected() {
        LayoutParser.parse("""{"type":"tabs","tabs":[]}""")
    }

    private fun inline(widget: Widget) = widget.meta.inline.associate { it.property to it.value }

    @Test
    fun parsesStylingHooks() {
        val widget = LayoutParser.parse(
            """{"type":"text","id":"title","class":"name  big","style":{"letter-spacing":"1px","--glow":"#f00"},
               "classIf":{"low":"${'$'}hpLow"},"bindStyle":{"color":"{typeColor}"}}""",
        )
        assertEquals("title", widget.meta.id)
        assertEquals(setOf("name", "big"), widget.meta.classes)
        assertEquals("1px", inline(widget)["letter-spacing"])
        assertEquals("#f00", inline(widget)["--glow"])
        assertEquals(mapOf("low" to "hpLow"), widget.meta.classIf)
        assertEquals(mapOf("color" to "{typeColor}"), widget.meta.bindStyle)
    }

    @Test
    fun styleMayBeACssString() {
        val widget = LayoutParser.parse("""{"type":"box","style":"padding: 4px; border-radius: 50%"}""")
        assertEquals(mapOf("padding" to "4px", "border-radius" to "50%"), inline(widget))
    }

    @Test
    fun explicitLegacyKeysBecomeInlineCssButDefaultsDoNot() {
        val text = LayoutParser.parse("""{"type":"text","size":18,"bold":true,"color":"#80FF0000"}""")
        assertEquals("18px", inline(text)["font-size"])
        assertEquals("700", inline(text)["font-weight"])
        assertEquals("rgba(255, 0, 0, 0.502)", inline(text)["color"]) // Android #AARRGGBB kept meaning the same
        assertTrue(LayoutParser.parse("""{"type":"text","text":"x"}""").meta.inline.isEmpty())

        val box = LayoutParser.parse("""{"type":"box","width":340,"background":"#192134","cornerRadius":16,"borderColor":"#DC2626","borderWidth":2,"padding":12}""")
        assertEquals(
            mapOf("width" to "340px", "padding" to "12px", "border-radius" to "16px", "background-color" to "#192134", "border" to "2px solid #DC2626"),
            inline(box),
        )
        assertEquals(mapOf("width" to "32px", "height" to "32px"), inline(LayoutParser.parse("""{"type":"image","size":32}""")))
        assertEquals("\"${LayoutParser.ASSET_FONT_PREFIX}fonts/a.ttf\"", inline(LayoutParser.parse("""{"type":"text","font":"fonts/a.ttf"}"""))["font-family"])
    }

    @Test
    fun invalidInlineCssIsReportedAndSkipped() {
        val parsed = LayoutParser.parseWithWarnings("""{"type":"box","style":{"colr":"red","width":"wide","height":"4px"}}""")
        assertEquals(mapOf("height" to "4px"), parsed.root.meta.inline.associate { it.property to it.value })
        assertEquals(2, parsed.warnings.size)
    }

    @Test
    fun parsesTextWithFont() {
        val widget = LayoutParser.parse(
            """{"type":"text","text":"hi","font":"fonts/PressStart2P.ttf"}""",
        ) as Widget.Text
        assertEquals("fonts/PressStart2P.ttf", widget.font)
    }

    @Test
    fun textWithoutFontIsNull() {
        val widget = LayoutParser.parse("""{"type":"text","text":"hi"}""") as Widget.Text
        assertNull(widget.font)
    }
}
