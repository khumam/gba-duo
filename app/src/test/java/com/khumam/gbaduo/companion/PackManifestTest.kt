package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestTest {

    private val validJson = """
        {
          "id": "demo-pipeline",
          "name": "Pipeline Demo",
          "version": "1.0.0",
          "author": "khumam",
          "formatVersion": 1,
          "match": { "gameCode": ["BPRE", "BPGE"], "crc32": ["0x1A2B3C4D"] },
          "tickHz": 4,
          "layout": "layout.json",
          "values": {
            "money": { "addr": "0x02025F24", "type": "u32" },
            "rupees": { "ptr": "0x03001000", "offset": 16, "type": "u16", "mask": "0x0FFF", "shift": 2 }
          }
        }
    """.trimIndent()

    @Test
    fun parsesAllFields() {
        val manifest = PackManifest.parse(validJson)
        assertEquals("demo-pipeline", manifest.id)
        assertEquals("Pipeline Demo", manifest.name)
        assertEquals(listOf("BPRE", "BPGE"), manifest.gameCodes)
        assertEquals(listOf(0x1A2B3C4DL), manifest.crc32s)
        assertEquals(4, manifest.tickHz)
        assertEquals("layout.json", manifest.layoutFile)

        val money = manifest.values.getValue("money")
        assertEquals(0x02025F24L, money.addr)
        assertEquals("u32", money.type)

        val rupees = manifest.values.getValue("rupees")
        assertEquals(0x03001000L, rupees.ptr)
        assertEquals(16, rupees.offset)
        assertEquals(0x0FFFL, rupees.mask)
        assertEquals(2, rupees.shift)
    }

    @Test
    fun scriptFieldIsOptional() {
        assertEquals(null, PackManifest.parse(validJson).scriptFile)
        val withScript = PackManifest.parse(validJson.replace("\"layout\"", "\"script\": \"main.lua\", \"layout\""))
        assertEquals("main.lua", withScript.scriptFile)
    }

    @Test
    fun matchesByGameCodeAndOptionalCrc() {
        val manifest = PackManifest.parse(validJson)
        assertTrue(manifest.matches("BPRE", 0x1A2B3C4DL))
        assertFalse(manifest.matches("BPRE", 0x99999999L))
        assertFalse(manifest.matches("AAAA", 0x1A2B3C4DL))
    }

    @Test
    fun emptyCrc32ListMatchesAnyCrc() {
        val json = validJson.replace("""["0x1A2B3C4D"]""", "[]")
        val manifest = PackManifest.parse(json)
        assertTrue(manifest.matches("BPRE", 0L))
        assertTrue(manifest.matches("BPRE", 0x7777L))
    }

    @Test(expected = PackManifest.InvalidManifestException::class)
    fun rejectsUnsupportedFormatVersion() {
        PackManifest.parse(validJson.replace(""""formatVersion": 1""", """"formatVersion": 99"""))
    }

    @Test
    fun validIds() {
        assertTrue(PackManifest.isValidId("demo-pipeline"))
        assertTrue(PackManifest.isValidId("a"))
        assertTrue(PackManifest.isValidId("pack-123"))
    }

    @Test
    fun invalidIds() {
        assertFalse(PackManifest.isValidId(""))
        assertFalse(PackManifest.isValidId("../saves"))
        assertFalse(PackManifest.isValidId("a/b"))
        assertFalse(PackManifest.isValidId("Has Spaces"))
        assertFalse(PackManifest.isValidId("UPPER"))
        assertFalse(PackManifest.isValidId("a".repeat(65)))
    }
}
