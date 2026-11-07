package com.khumam.gbaduo.emu

import com.khumam.gbaduo.storage.GameSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun builtInsAlwaysRoute() {
        assertEquals(ShaderRoute.Builtin(ShaderPreset.LCD), ShaderRoute.pick(ShaderChoice.Builtin(ShaderPreset.LCD), es3Available = false, chainCreated = false, error = null))
    }

    @Test
    fun aLibraryShaderRunsOnlyWithEs3AndAChain() {
        val choice = ShaderChoice.Library("crt/crt-geom.slangp")
        assertEquals(ShaderRoute.Chain("crt/crt-geom.slangp"), ShaderRoute.pick(choice, es3Available = true, chainCreated = true, error = null))

        val old = ShaderRoute.pick(choice, es3Available = false, chainCreated = true, error = null)
        assertTrue(old is ShaderRoute.KeepPrevious)
        assertNotNull((old as ShaderRoute.KeepPrevious).error)
        assertTrue(old.error!!.contains("OpenGL ES 3"))

        val failed = ShaderRoute.pick(choice, es3Available = true, chainCreated = false, error = "fragment shader: boom")
        assertEquals(ShaderRoute.KeepPrevious("fragment shader: boom"), failed)

        val preparing = ShaderRoute.pick(choice, es3Available = true, chainCreated = false, error = null)
        assertEquals(ShaderRoute.KeepPrevious(null), preparing)
    }
}
