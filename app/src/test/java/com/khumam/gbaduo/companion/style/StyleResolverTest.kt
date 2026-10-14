package com.khumam.gbaduo.companion.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleResolverTest {

    private fun css(text: String) = CssParser.parse(text)
    private fun decls(text: String) = CssParser.parseDeclarations(text, null)

    private fun resolveChain(resolver: StyleResolver, vararg nodes: StyleNode): List<ComputedStyle> {
        for (i in 1 until nodes.size) nodes[i - 1].add(nodes[i])
        val out = mutableListOf<ComputedStyle>()
        var parent: ComputedStyle? = null
        for (n in nodes) {
            val style = resolver.resolve(n, parent)
            out += style
            parent = style
        }
        return out
    }

    @Test
    fun cascadeOrderBuiltInDefaultsPackInlineBound() {
        val resolver = StyleResolver(
            builtIn = listOf(css("box { width: 1px; height: 1px; opacity: .1 }")),
            pack = listOf(css("box { height: 3px } .x { opacity: .3 }")),
        )
        val node = StyleNode(
            "box",
            staticClasses = setOf("x"),
            defaults = decls("width: 2px; height: 2px; padding-top: 2px"),
            inline = decls("padding-top: 4px; margin-top: 4px"),
        )
        node.bound = decls("margin-top: 5px")
        val style = resolver.resolve(node, null)
        assertEquals("2px", style.raw("width"))      // defaults beat built-in rules
        assertEquals("3px", style.raw("height"))     // pack rules beat defaults
        assertEquals(".3", style.raw("opacity"))
        assertEquals("4px", style.raw("padding-top")) // inline beats rules/defaults
        assertEquals("5px", style.raw("margin-top"))  // bound beats inline
    }

    @Test
    fun specificityThenSourceOrder() {
        val resolver = StyleResolver(emptyList(), listOf(css("#a { color: red } .b { color: blue } box { color: green } .b { color: white }")))
        val style = resolver.resolve(StyleNode("box", id = "a", staticClasses = setOf("b")), null)
        assertEquals("red", style.raw("color"))
        val style2 = resolver.resolve(StyleNode("box", staticClasses = setOf("b")), null)
        assertEquals("white", style2.raw("color"))
    }

    @Test
    fun inheritanceOfTextPropertiesOnly() {
        val resolver = StyleResolver(emptyList(), listOf(css(".card { color: #abc; padding: 8px; font-family: Pixel }")))
        val (_, text) = resolveChain(resolver, StyleNode("box", staticClasses = setOf("card")), StyleNode("text"))
        assertEquals("#abc", text.raw("color"))
        assertEquals("Pixel", text.raw("font-family"))
        assertFalse(text.isSet("padding-top"))
        assertEquals(0f, text.padding("top"))
    }

    @Test
    fun customPropertiesInheritAndSubstitute() {
        val resolver = StyleResolver(
            emptyList(),
            listOf(css(":root { --accent: #e63946; --gap: 6px } .card { border: 2px solid var(--accent); gap: var(--gap) var(--missing, 3px) } .bad { color: var(--nope) }")),
        )
        val root = StyleNode("column")
        val card = StyleNode("box", staticClasses = setOf("card", "bad"))
        val (_, cardStyle) = resolveChain(resolver, root, card)
        assertEquals("#e63946", cardStyle.raw("border-top-color"))
        assertEquals(0xFFE63946.toInt(), cardStyle.color("border-left-color"))
        assertEquals("6px", cardStyle.raw("row-gap"))
        assertEquals("3px", cardStyle.raw("column-gap"))
        assertFalse(cardStyle.isSet("color").and(cardStyle.raw("color") == "var(--nope)"))
        assertTrue(resolver.warnings.any { "no value" in it })
    }

    @Test
    fun customPropertyCanReferenceAnother() {
        val resolver = StyleResolver(emptyList(), listOf(css(":root { --base: 10px; --double: calc(0); --pad: var(--base) } box { padding: var(--pad) }")))
        val (_, box) = resolveChain(resolver, StyleNode("column"), StyleNode("box"))
        assertEquals(10f, box.padding("left"))
    }

    @Test
    fun fontSizeEmAndInheritanceDoNotCompound() {
        val resolver = StyleResolver(emptyList(), listOf(css(":root { font-size: 10px } .big { font-size: 1.5em; letter-spacing: .1em } .rem { font-size: 2rem }")))
        val (root, big, child, rem) = resolveChain(resolver, StyleNode("column"), StyleNode("box", staticClasses = setOf("big")), StyleNode("text"), StyleNode("text", staticClasses = setOf("rem")))
        assertEquals(10f, root.fontSizePx)
        assertEquals(15f, big.fontSizePx)
        assertEquals(15f, child.fontSizePx) // inherits 15px, not 1.5em of 15
        assertEquals(1.5f, child.letterSpacingPx, 0.001f) // computed .1em of 15px, inherited as px
        assertEquals(20f, rem.fontSizePx)
    }

    @Test
    fun fontWeightKeywords() {
        val resolver = StyleResolver(emptyList(), listOf(css(".b { font-weight: bold } .er { font-weight: bolder }")))
        val (_, b, er, plain) = resolveChain(resolver, StyleNode("column"), StyleNode("box", staticClasses = setOf("b")), StyleNode("box", staticClasses = setOf("er")), StyleNode("text"))
        assertEquals(700, b.fontWeight)
        assertEquals(900, er.fontWeight)
        assertEquals(900, plain.fontWeight)
    }

    @Test
    fun inheritInitialUnsetKeywords() {
        val resolver = StyleResolver(emptyList(), listOf(css(".p { padding: 5px; color: red } .i { padding: inherit; color: initial } .u { color: unset }")))
        val (_, i, u) = resolveChain(resolver, StyleNode("box", staticClasses = setOf("p")), StyleNode("box", staticClasses = setOf("i")), StyleNode("box", staticClasses = setOf("u")))
        assertEquals(5f, i.padding("top"))
        assertEquals(0xFFFFFFFF.toInt(), i.color)
        assertEquals(0xFFFFFFFF.toInt(), u.color) // unset on an inherited property inherits (from .i)
    }

    @Test
    fun statesAndDynamicClassesChangeTheResult() {
        val resolver = StyleResolver(emptyList(), listOf(css(".tab { color: #888 } .tab:active { color: #fff } .low { opacity: .5 }")))
        val tab = StyleNode("text", staticClasses = setOf("tab"))
        assertEquals("#888", resolver.resolve(tab, null).raw("color"))
        tab.states = setOf(PseudoState.ACTIVE)
        assertEquals("#fff", resolver.resolve(tab, null).raw("color"))
        tab.dynamicClasses = setOf("low")
        assertEquals(0.5f, resolver.resolve(tab, null).opacity)
    }

    @Test
    fun currentColorAndBorderWidths() {
        val resolver = StyleResolver(emptyList(), listOf(css("box { color: lime; border: 2px solid } .n { border: 4px none red }")))
        val style = resolver.resolve(StyleNode("box"), null)
        assertEquals(0xFF00FF00.toInt(), style.color("border-top-color"))
        assertEquals(2f, style.borderWidth("top"))
        assertEquals(0f, resolver.resolve(StyleNode("box", staticClasses = setOf("n")), null).borderWidth("top"))
    }

    @Test
    fun equalStylesCompareEqual() {
        val resolver = StyleResolver(emptyList(), listOf(css("box { width: 10px }")))
        assertEquals(resolver.resolve(StyleNode("box"), null), resolver.resolve(StyleNode("box"), null))
        assertFalse(resolver.resolve(StyleNode("box"), null) == resolver.resolve(StyleNode("text"), null))
    }
}
