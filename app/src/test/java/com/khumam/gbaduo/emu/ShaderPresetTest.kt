package com.khumam.gbaduo.emu

import org.junit.Assert.assertEquals
import org.junit.Test

class ShaderPresetTest {

    @Test
    fun byIdFindsMatchingPreset() {
        assertEquals(ShaderPreset.GBA_COLOR, ShaderPreset.byId("gba_color"))
        assertEquals(ShaderPreset.SCALE2X, ShaderPreset.byId("scale2x"))
    }

    @Test
    fun byIdFallsBackToOffForUnknownOrNull() {
        assertEquals(ShaderPreset.OFF, ShaderPreset.byId("nonsense"))
        assertEquals(ShaderPreset.OFF, ShaderPreset.byId(null))
    }

    @Test
    fun nextCyclesThroughAllPresetsAndWrapsAround() {
        var preset = ShaderPreset.OFF
        val seen = mutableListOf(preset)
        repeat(ShaderPreset.entries.size - 1) {
            preset = ShaderPreset.next(preset)
            seen += preset
        }
        assertEquals(ShaderPreset.entries.toList(), seen)
        assertEquals(ShaderPreset.OFF, ShaderPreset.next(preset))
    }
}
