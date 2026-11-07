package com.khumam.gbaduo.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveImportTest {

    private fun tempDir(): File = Files.createTempDirectory("imp").toFile()
    private fun backups(dir: File) = dir.listFiles().orEmpty().filter { it.name.startsWith("game.sav.bak-") }

    @Test
    fun backsUpThenReplaces() {
        val game = tempDir()
        File(game, "game.sav").writeText("old")
        val r = SaveImport.import(game, "new".toByteArray(), null, "")
        assertTrue(r is SaveImport.Result.Done)
        assertEquals("new", File(game, "game.sav").readText())
        assertEquals("old", backups(game).single().readText())
        assertFalse(File(game, "game.sav.partial").exists())
    }

    @Test
    fun firstSaveNeedsNoBackup() {
        val game = tempDir()
        assertTrue(SaveImport.import(game, byteArrayOf(1, 2), null, "") is SaveImport.Result.Done)
        assertArrayEquals(byteArrayOf(1, 2), File(game, "game.sav").readBytes())
        assertTrue(backups(game).isEmpty())
    }

    @Test
    fun rejectsEmptyAndHuge() {
        val game = tempDir()
        File(game, "game.sav").writeText("old")
        assertTrue(SaveImport.import(game, ByteArray(0), null, "") is SaveImport.Result.Invalid)
        assertTrue(SaveImport.import(game, ByteArray(SaveImport.MAX_SIZE + 1), null, "") is SaveImport.Result.Invalid)
        assertEquals("old", File(game, "game.sav").readText())
    }

    @Test
    fun saveFolderGetsBackupAndNewSave() {
        val game = tempDir(); val remote = tempDir()
        File(game, "game.sav").writeText("old")
        File(remote, "game.sav").writeText("old")
        val r = SaveImport.import(game, "new".toByteArray(), FileSyncDir(remote), "root") as SaveImport.Result.Done
        assertTrue(r.synced)
        assertEquals("new", File(game, "game.sav").readText())
        assertEquals("new", File(remote, "game.sav").readText())
        assertEquals("old", backups(remote).single().readText())
        assertEquals("old", backups(game).single().readText())
    }
}
