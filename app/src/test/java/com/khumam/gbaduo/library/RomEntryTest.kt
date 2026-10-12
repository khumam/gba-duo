package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class RomEntryTest {

    @Test
    fun parsesGameCodeAndTitleFromHeader() {
        val header = ByteArray(0xC0)
        "POKEMON FIRE".toByteArray().copyInto(header, 0xA0)
        "BPRE".toByteArray().copyInto(header, 0xAC)

        val entry = RomEntry.fromHeader("pokefirered.gba", header)

        assertEquals("POKEMON FIRE", entry.title)
        assertEquals("BPRE", entry.gameCode)
        assertEquals("pokefirered.gba", entry.fileName)
    }

    @Test
    fun fallsBackToFileNameWhenTitleIsBlank() {
        val header = ByteArray(0xC0)
        val entry = RomEntry.fromHeader("mystery.gba", header)
        assertEquals("mystery.gba", entry.title)
    }

    @Test
    fun savePathStripsExtensionAndIsSafeAsFileName() {
        val entry = RomEntry.fromHeader("My Game (v1.1).gba", ByteArray(0xC0))
        assertEquals("My Game (v1.1).sav", entry.saveFileName())
    }
}
