package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomHeaderTest {

    private fun gbaHeader(title: String, code: String): ByteArray {
        val header = ByteArray(RomHeader.SIZE)
        title.toByteArray().copyInto(header, 0xA0)
        code.toByteArray().copyInto(header, 0xAC)
        header[0xB2] = 0x96.toByte()
        return header
    }

    private fun gbHeader(title: String, cgbFlag: Int = 0, newLicenseeMaker: String? = null): ByteArray {
        val header = ByteArray(RomHeader.SIZE)
        byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66).copyInto(header, 0x104)
        title.toByteArray().copyInto(header, 0x134)
        if (newLicenseeMaker != null) {
            newLicenseeMaker.toByteArray().copyInto(header, 0x13F)
            header[0x14B] = 0x33
        }
        header[0x143] = cgbFlag.toByte()
        return header
    }

    @Test
    fun parsesGbaHeader() {
        val header = RomHeader.parse("emerald.gba", gbaHeader("POKEMON EMER", "BPEE"))
        assertEquals(Platform.GBA, header.platform)
        assertEquals("POKEMON EMER", header.title)
        assertEquals("BPEE", header.gameCode)
    }

    @Test
    fun parsesDmgHeaderWithOldLicensee() {
        val header = RomHeader.parse("tetris.gb", gbHeader("TETRIS"))
        assertEquals(Platform.GB, header.platform)
        assertEquals("TETRIS", header.title)
        assertEquals("DMG-????", header.gameCode)
    }

    @Test
    fun cgbOnlyFlagIsGbcWithCgbCode() {
        val header = RomHeader.parse("crystal.gbc", gbHeader("PM_CRYSTAL", cgbFlag = 0xC0, newLicenseeMaker = "BYTE"))
        assertEquals(Platform.GBC, header.platform)
        assertEquals("PM_CRYSTAL", header.title)
        assertEquals("CGB-BYTE", header.gameCode)
    }

    @Test
    fun dualModeFlagIsGbcButKeepsMgbaDmgPrefix() {
        // mGBA's GBGetGameCode only uses "CGB-" for CGB-only (0xC0) carts.
        val header = RomHeader.parse("dx.gbc", gbHeader("ZELDA", cgbFlag = 0x80))
        assertEquals(Platform.GBC, header.platform)
        assertEquals("DMG-????", header.gameCode)
    }

    @Test
    fun cgbFlagByteIsNotPartOfTitle() {
        val header = RomHeader.parse("x.gbc", gbHeader("FIFTEEN_CHARS__", cgbFlag = 0x80))
        assertEquals("FIFTEEN_CHARS__", header.title)
    }

    @Test
    fun headerWinsOverExtension() {
        assertEquals(Platform.GB, RomHeader.parse("misnamed.gba", gbHeader("TETRIS")).platform)
        assertEquals(Platform.GBA, RomHeader.parse("misnamed.gb", gbaHeader("TITLE", "AXVE")).platform)
    }

    @Test
    fun unrecognisedHeaderFallsBackToExtension() {
        assertEquals(Platform.GBC, RomHeader.parse("homebrew.GBC", ByteArray(RomHeader.SIZE)).platform)
        assertEquals(Platform.GB, RomHeader.parse("homebrew.gb", ByteArray(RomHeader.SIZE)).platform)
        assertEquals(Platform.GBA, RomHeader.parse("homebrew.gba", ByteArray(RomHeader.SIZE)).platform)
    }

    @Test
    fun shortHeaderStillParsesAsGba() {
        // Older callers read only 0xC0 bytes.
        val header = ByteArray(0xC0)
        "POKEMON FIRE".toByteArray().copyInto(header, 0xA0)
        "BPRE".toByteArray().copyInto(header, 0xAC)
        val parsed = RomHeader.parse("pokefirered.gba", header)
        assertEquals(Platform.GBA, parsed.platform)
        assertEquals("POKEMON FIRE", parsed.title)
        assertEquals("BPRE", parsed.gameCode)
    }

    @Test
    fun blankTitleFallsBackToFileName() {
        assertEquals("mystery.gb", RomHeader.parse("mystery.gb", gbHeader("")).title)
    }

    @Test
    fun recognisesRomExtensionsCaseInsensitively() {
        assertTrue(RomHeader.isRomFileName("a.gba"))
        assertTrue(RomHeader.isRomFileName("a.GB"))
        assertTrue(RomHeader.isRomFileName("a.Gbc"))
        assertFalse(RomHeader.isRomFileName("a.sav"))
        assertFalse(RomHeader.isRomFileName("gba"))
    }
}
