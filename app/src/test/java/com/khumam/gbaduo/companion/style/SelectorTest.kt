package com.khumam.gbaduo.companion.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorTest {

    private fun sel(text: String) = Selector.parse(text) ?: error("did not parse: $text")

    private fun tree(): Triple<StyleNode, StyleNode, List<StyleNode>> {
        val root = StyleNode("column", id = "root", staticClasses = setOf("screen"))
        val list = StyleNode("list", staticClasses = setOf("party"))
        root.add(list)
        val items = (0 until 4).map { i -> StyleNode("box", staticClasses = setOf("mon")).also { list.add(it) } }
        return Triple(root, list, items)
    }

    @Test
    fun typeClassIdAndCompound() {
        val (root, list, items) = tree()
        assertTrue(sel("column").matches(root))
        assertTrue(sel("#root").matches(root))
        assertTrue(sel(".party").matches(list))
        assertTrue(sel("list.party").matches(list))
        assertFalse(sel("box.party").matches(list))
        assertTrue(sel("*").matches(items[0]))
        items[1].dynamicClasses = setOf("fainted")
        assertTrue(sel(".mon.fainted").matches(items[1]))
        assertFalse(sel(".mon.fainted").matches(items[0]))
    }

    @Test
    fun descendantAndChildCombinators() {
        val (_, _, items) = tree()
        assertTrue(sel(".screen .mon").matches(items[0]))
        assertTrue(sel("column > list > box").matches(items[0]))
        assertFalse(sel("column > box").matches(items[0]))
        assertTrue(sel("column  >  list   .mon").matches(items[2]))
    }

    @Test
    fun structuralPseudoClasses() {
        val (root, _, items) = tree()
        assertTrue(sel(":root").matches(root))
        assertFalse(sel(":root").matches(items[0]))
        assertTrue(sel(".mon:first-child").matches(items[0]))
        assertTrue(sel(".mon:last-child").matches(items[3]))
        assertEquals(listOf(true, false, true, false), items.map { sel(".mon:nth-child(odd)").matches(it) })
        assertEquals(listOf(false, true, false, true), items.map { sel(".mon:nth-child(2n)").matches(it) })
        assertEquals(listOf(true, true, false, false), items.map { sel(".mon:nth-child(-n+2)").matches(it) })
        assertEquals(listOf(false, false, true, false), items.map { sel(".mon:nth-child(3)").matches(it) })
        assertEquals(listOf(false, false, true, true), items.map { sel(".mon:nth-last-child(-n+2)").matches(it) })
        assertTrue(sel(".mon:empty").matches(items[0]))
    }

    @Test
    fun statePseudoClasses() {
        val (_, _, items) = tree()
        assertFalse(sel(".mon:pressed").matches(items[0]))
        items[0].states = setOf(PseudoState.PRESSED)
        assertTrue(sel(".mon:pressed").matches(items[0]))
        assertFalse(sel(".mon:active").matches(items[0]))
    }

    @Test
    fun specificityOrdering() {
        assertTrue(sel("#a").specificity > sel(".a.b.c").specificity)
        assertTrue(sel(".a").specificity > sel("box text").specificity)
        assertTrue(sel(".a:first-child").specificity > sel(".a").specificity)
        assertEquals(Specificity(0, 0, 0), sel("*").specificity)
    }

    @Test
    fun unsupportedSelectorsAreRejected() {
        assertNull(Selector.parse("a + b"))
        assertNull(Selector.parse("a ~ b"))
        assertNull(Selector.parse("p::before"))
        assertNull(Selector.parse("[data-x]"))
        assertNull(Selector.parse(":hover-ish"))
        assertNull(Selector.parse("a >"))
        assertNull(Selector.parseList("a, , b"))
        assertEquals(2, Selector.parseList(".a, .b")!!.size)
    }
}
