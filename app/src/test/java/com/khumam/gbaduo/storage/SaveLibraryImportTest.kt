package com.khumam.gbaduo.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveLibraryImportTest {

    private fun tempDir(): File = Files.createTempDirectory("lib").toFile()

    @Test
    fun importsLooseSaveFromRoot() {
        val root = tempDir(); val game = tempDir()
        File(root, "Pokemon.sav").writeText("old")
        assertTrue(SaveLibrary.importLooseSave(FileSyncDir(root), "gba", "Pokemon.gba", FileSyncDir(game)))
        assertEquals("old", File(game, "game.sav").readText())
        assertTrue(File(root, "Pokemon.sav").exists())
    }

    @Test
    fun importsFromPlatformFolderIgnoringCase() {
        val root = tempDir(); val game = tempDir()
        File(root, "GBA").mkdirs()
        File(root, "GBA/pokemon.SAV").writeText("old")
        assertTrue(SaveLibrary.importLooseSave(FileSyncDir(root), "gba", "Pokemon.gba", FileSyncDir(game)))
        assertEquals("old", File(game, "game.sav").readText())
    }

    @Test
    fun neverOverwritesExistingSave() {
        val root = tempDir(); val game = tempDir()
        File(root, "Pokemon.sav").writeText("old")
        File(game, "game.sav").writeText("progress")
        assertFalse(SaveLibrary.importLooseSave(FileSyncDir(root), "gba", "Pokemon.gba", FileSyncDir(game)))
        assertEquals("progress", File(game, "game.sav").readText())
    }

    @Test
    fun otherRomsSaveIsNotUsed() {
        val root = tempDir(); val game = tempDir()
        File(root, "Pokemon Gold.sav").writeText("gold")
        assertFalse(SaveLibrary.importLooseSave(FileSyncDir(root), "gba", "Pokemon.gba", FileSyncDir(game)))
    }

    @Test
    fun syncGameTakesLooseSaveIntoPrivateCopy() {
        val files = tempDir(); val root = tempDir()
        val storage = GameStorage.forGame(files, com.khumam.gbaduo.library.Platform.GBA, "POKEMON", "BPEE", 0x1234L)
        storage.ensureDirs()
        File(root, "Pokemon.sav").writeText("old")
        SaveLibrary.syncGame(storage, FileSyncDir(root), "r", SaveSync.Direction.BOTH, "Pokemon.gba")
        assertEquals("old", storage.saveFile.readText())
    }
}
