package com.khumam.gbaduo.emu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShaderChoiceTest {

    private fun library(vararg downloaded: String): ShaderLibrary {
        val root = Files.createTempDirectory("slang").toFile()
        for (path in downloaded) File(root, path).apply { parentFile.mkdirs(); writeText("x") }
        return ShaderLibrary(root)
    }

    @Test
    fun parseReadsBuiltInsAndLibraryIds() {
        assertEquals(ShaderChoice.Builtin(ShaderPreset.LCD), ShaderChoice.parse("lcd"))
        val lib = ShaderChoice.parse("slang:crt/crt-geom.slangp")
        assertEquals(ShaderChoice.Library("crt/crt-geom.slangp"), lib)
        assertEquals("crt-geom", lib.displayName)
        assertEquals("slang:crt/crt-geom.slangp", lib.id)
        assertEquals("LCD Grid", ShaderChoice.parse("lcd").displayName)
    }

    @Test
    fun parseTurnsUnknownOrBrokenIdsIntoOff() {
        for (id in listOf(null, "", "nonsense", "slang:", "slang:../x.slangp", "slang:/abs.slangp", "slang:a/../../x.slangp")) {
            assertEquals("id $id", ShaderChoice.OFF, ShaderChoice.parse(id))
        }
    }

    @Test
    fun libraryRefusesPathsOutsideItsRoot() {
        val lib = library()
        try {
            lib.file("../x")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        assertFalse(lib.isDownloaded("../x"))
        assertTrue(lib.file("crt/a.slangp").path.startsWith(lib.root.path))
    }

    @Test
    fun libraryKnowsWhatIsDownloadedAndDeletes() {
        val lib = library("crt/a.slangp")
        assertTrue(lib.isDownloaded("crt/a.slangp"))
        assertFalse(lib.isDownloaded("crt/b.slangp"))
        assertTrue(lib.delete("crt/a.slangp"))
        assertFalse(lib.isDownloaded("crt/a.slangp"))
    }

    @Test
    fun gamePickWinsThenDefaultThenNone() {
        val lib = library("crt/a.slangp", "crt/b.slangp")
        val game = ShaderSettings.resolve("slang:crt/a.slangp", "slang:crt/b.slangp", lib)
        assertEquals(ShaderChoice.Library("crt/a.slangp"), game.choice)
        assertEquals(InUse.Source.GAME, game.source)

        val builtinGame = ShaderSettings.resolve("scanlines", "lcd", lib)
        assertEquals(ShaderChoice.Builtin(ShaderPreset.SCANLINES), builtinGame.choice)
        assertEquals(InUse.Source.GAME, builtinGame.source)

        val followsDefault = ShaderSettings.resolve("off", "slang:crt/b.slangp", lib)
        assertEquals(ShaderChoice.Library("crt/b.slangp"), followsDefault.choice)
        assertEquals(InUse.Source.DEFAULT, followsDefault.source)

        val none = ShaderSettings.resolve(null, "off", lib)
        assertEquals(ShaderChoice.OFF, none.choice)
        assertEquals(InUse.Source.NONE, none.source)
    }

    @Test
    fun aSavedLibraryShaderWhoseFilesAreGoneBehavesAsOffAndIsFlagged() {
        val lib = library("crt/b.slangp")
        val gone = ShaderSettings.resolve("slang:crt/gone.slangp", null, lib)
        assertEquals(ShaderChoice.OFF, gone.choice)
        assertEquals(InUse.Source.NONE, gone.source)
        assertEquals(ShaderChoice.Library("crt/gone.slangp"), gone.missing)

        // the game's missing pick falls back to the default, like an Off game does
        val fallsBack = ShaderSettings.resolve("slang:crt/gone.slangp", "slang:crt/b.slangp", lib)
        assertEquals(ShaderChoice.Library("crt/b.slangp"), fallsBack.choice)
        assertEquals(InUse.Source.DEFAULT, fallsBack.source)
        assertEquals(ShaderChoice.Library("crt/gone.slangp"), fallsBack.missing)

        assertNull(ShaderSettings.resolve("lcd", null, lib).missing)
    }
}
