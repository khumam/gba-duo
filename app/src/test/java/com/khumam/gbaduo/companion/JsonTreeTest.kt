package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTreeTest {

    @Test
    fun parsesFlatObject() {
        val tree = JsonTree.parseObject("""{"money":500,"name":"Red"}""")
        assertEquals(500L, tree["money"])
        assertEquals("Red", tree["name"])
    }

    @Test
    fun parsesNestedObjectsAndArrays() {
        val tree = JsonTree.parseObject("""{"party":[{"name":"Bulbasaur","hp":20},{"name":"Pidgey","hp":12}]}""")
        @Suppress("UNCHECKED_CAST")
        val party = tree["party"] as List<Map<String, Any?>>
        assertEquals(2, party.size)
        assertEquals("Bulbasaur", party[0]["name"])
        assertEquals(20L, party[0]["hp"])
        assertEquals("Pidgey", party[1]["name"])
    }

    @Test
    fun convertsJsonNullToKotlinNull() {
        val tree = JsonTree.parseObject("""{"item":null}""")
        assertNull(tree["item"])
    }

    @Test
    fun convertsBooleansAndDoubles() {
        val tree = JsonTree.parseObject("""{"alive":true,"ratio":0.5}""")
        assertEquals(true, tree["alive"])
        assertEquals(0.5, tree["ratio"] as Double, 0.0001)
    }

    @Test
    fun emptyObjectParsesToEmptyMap() {
        val tree = JsonTree.parseObject("{}")
        assertEquals(emptyMap<String, Any?>(), tree)
    }
}
