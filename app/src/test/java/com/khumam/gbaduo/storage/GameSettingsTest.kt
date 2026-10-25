package com.khumam.gbaduo.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameSettingsTest {

    private fun file(): File = File(Files.createTempDirectory("settings").toFile(), "settings.json")

    @Test
    fun missingFileIsEmpty() {
        assertNull(GameSettings(file()).getString("shader"))
        assertNull(GameSettings(file()).getInt("slot"))
    }

    @Test
    fun putKeepsOtherKeys() {
        val f = file()
        val settings = GameSettings(f)
        settings.put("shader", "lcd")
        settings.put("slot", 3)
        val json = JSONObject(f.readText())
        assertEquals("lcd", json.getString("shader"))
        assertEquals(3, json.getInt("slot"))
        assertEquals("lcd", GameSettings(f).getString("shader"))
        assertEquals(3, GameSettings(f).getInt("slot"))
    }

    @Test
    fun nullRemoves() {
        val f = file()
        val settings = GameSettings(f)
        settings.put("shader", "lcd")
        settings.put("shader", null)
        assertFalse(JSONObject(f.readText()).has("shader"))
        assertNull(settings.getString("shader"))
    }

    @Test
    fun brokenFileIsEmptyAndRepairedOnPut() {
        val f = file()
        f.writeText("x")
        val settings = GameSettings(f)
        assertNull(settings.getInt("slot"))
        settings.put("slot", 2)
        assertEquals("""{"slot":2}""", f.readText())
        assertTrue(!File(f.parentFile, "settings.json.tmp").exists())
    }

    @Test
    fun artworkLockRoundTripsAndKeepsOtherKeys() {
        val settings = GameSettings(file())
        settings.put(GameSettingsKeys.SHADER, "lcd")
        settings.put(GameSettingsKeys.ARTWORK, GameSettingsKeys.ARTWORK_PICKED)
        assertEquals("picked", settings.getString(GameSettingsKeys.ARTWORK))
        assertEquals("lcd", settings.getString(GameSettingsKeys.SHADER))
        settings.put(GameSettingsKeys.ARTWORK, null)
        assertNull(settings.getString(GameSettingsKeys.ARTWORK))
        assertEquals("lcd", settings.getString(GameSettingsKeys.SHADER))
    }
}
