package com.khumam.gbaduo.emu

import com.khumam.gbaduo.storage.GameSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class GameShaderTest {

    @Test
    fun noChoiceOrUnknownChoiceIsOff() {
        assertEquals(ShaderPreset.OFF, ShaderPreset.byId(null))
        assertEquals(ShaderPreset.OFF, ShaderPreset.byId("removed_in_a_later_version"))
    }

    @Test
    fun gameChoiceSurvivesThroughSettingsFile() {
        val file = Files.createTempDirectory("g").toFile().resolve("settings.json")
        GameSettings(file).put("shader", ShaderPreset.LCD.id)
        assertEquals(ShaderPreset.LCD, ShaderPreset.byId(GameSettings(file).getString("shader")))
    }
}
