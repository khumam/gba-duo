package com.khumam.gbaduo.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGenerationTest {
    @Test fun `a token is current until the next one is issued`() {
        val gen = LoadGeneration()
        val first = gen.next()
        assertTrue(gen.isCurrent(first))
        val second = gen.next()
        assertFalse(gen.isCurrent(first))
        assertTrue(gen.isCurrent(second))
    }
}
