package com.khumam.gbaduo.library.artwork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArtworkStoreTest {

    private fun store() = ArtworkStore(Files.createTempDirectory("art").toFile())

    @Test
    fun unknownGameHasNoArtwork() {
        val s = store()
        assertNull(s.meta("gba", 1))
        assertNull(s.file("gba", 1))
    }

    @Test
    fun putThenReadBack() {
        val s = store()
        s.put("gba", 0xABCD, byteArrayOf(1, 2, 3), "folder", "gba/covers/x.png", 99L)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.file("gba", 0xABCD)!!.readBytes())
        assertEquals(ArtworkMeta("folder", "gba/covers/x.png", 99L), s.meta("gba", 0xABCD))
        assertNull(s.meta("gbc", 0xABCD))
    }

    @Test
    fun putReplacesAndLeavesNoTempFile() {
        val s = store()
        s.put("gba", 5, byteArrayOf(1), "folder", "a", 1L)
        s.put("gba", 5, byteArrayOf(9, 9), "steamgriddb", "77", 2L)
        assertArrayEquals(byteArrayOf(9, 9), s.file("gba", 5)!!.readBytes())
        assertEquals("steamgriddb", s.meta("gba", 5)!!.source)
        assertEquals(listOf("00000005.img"), s.file("gba", 5)!!.parentFile!!.list()!!.toList())
    }

    @Test
    fun removeDeletesFileAndEntry() {
        val s = store()
        s.put("gba", 5, byteArrayOf(1), "folder", "a", 1L)
        s.remove("gba", 5)
        assertNull(s.file("gba", 5))
        assertNull(s.meta("gba", 5))
    }

    @Test
    fun removeAllClearsEverythingAndCounts() {
        val s = store()
        s.put("gba", 1, byteArrayOf(1), "folder", "a", 1L)
        s.put("gb", 2, byteArrayOf(1), "folder", "b", 1L)
        assertEquals(2, s.count())
        s.removeAll()
        assertEquals(0, s.count())
        assertNull(s.file("gb", 2))
    }

    @Test
    fun generationChangesOnEveryChange() {
        val s = store()
        val g0 = s.generation()
        s.put("gba", 1, byteArrayOf(1), "folder", "a", 1L)
        val g1 = s.generation()
        s.remove("gba", 1)
        val g2 = s.generation()
        assertTrue(g1 > g0)
        assertTrue(g2 > g1)
    }

    @Test
    fun brokenIndexReadsAsEmpty() {
        val root = Files.createTempDirectory("art").toFile()
        File(root, "index.json").writeText("{nope")
        val s = ArtworkStore(root)
        assertNull(s.meta("gba", 1))
        s.put("gba", 1, byteArrayOf(1), "folder", "a", 1L)
        assertNotNull(s.meta("gba", 1))
        assertFalse(s.count() == 0)
    }
}
