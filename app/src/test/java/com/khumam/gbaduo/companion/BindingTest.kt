package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingTest {

    private val flat = mapOf("money" to 500L, "hp" to 0L)

    @Test
    fun resolvesFlatKey() {
        assertEquals(500L, Binding.resolve("money", flat))
        assertNull(Binding.resolve("missing", flat))
    }

    @Test
    fun resolvesDottedAndIndexedPaths() {
        val root = mapOf(
            "party" to listOf(
                mapOf("name" to "Bulbasaur", "hp" to 20L),
                mapOf("name" to "Pidgey", "hp" to 12L),
            ),
        )
        assertEquals("Bulbasaur", Binding.resolve("party[0].name", root))
        assertEquals(12L, Binding.resolve("party[1].hp", root))
        assertNull(Binding.resolve("party[5].name", root))
    }

    @Test
    fun interpolatesFlatAndNestedTokens() {
        assertEquals("Money: 500", Binding.interpolate("Money: {money}", flat))
        val root = mapOf("party" to listOf(mapOf("name" to "Bulbasaur")))
        assertEquals("First: Bulbasaur", Binding.interpolate("First: {party[0].name}", root))
    }

    @Test
    fun missingKeyInterpolatesToEmptyString() {
        assertEquals("Item: ", Binding.interpolate("Item: {missing}", flat))
    }

    @Test
    fun truthiness() {
        assertTrue(Binding.isTruthy(1L))
        assertFalse(Binding.isTruthy(0L))
        assertFalse(Binding.isTruthy(null))
        assertTrue(Binding.isTruthy("x"))
        assertFalse(Binding.isTruthy(""))
        assertTrue(Binding.isTruthy(listOf(1)))
        assertFalse(Binding.isTruthy(emptyList<Any?>()))
    }

    @Test
    fun asLongCoercesNumericTypes() {
        assertEquals(5L, Binding.asLong(5L))
        assertEquals(5L, Binding.asLong(5.9))
        assertEquals(5L, Binding.asLong("5"))
        assertEquals(0L, Binding.asLong(null))
    }

    @Test
    fun scopedValuesResolveItemFirstThenRoot() {
        val root = mapOf("teamName" to "Red's team")
        val item = mapOf("name" to "Bulbasaur")
        val scoped = ScopedValues(item = item, index = 2, root = root)

        assertEquals("Bulbasaur", Binding.resolve("name", scoped))
        assertEquals("Red's team", Binding.resolve("teamName", scoped))
        assertEquals(2L, Binding.resolve("@index", scoped))
    }
}
