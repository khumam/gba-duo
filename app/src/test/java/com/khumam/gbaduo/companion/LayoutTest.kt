package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
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
}
