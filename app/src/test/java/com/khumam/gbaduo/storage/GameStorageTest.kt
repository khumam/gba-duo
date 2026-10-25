package com.khumam.gbaduo.storage

import com.khumam.gbaduo.library.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameStorageTest {

    private companion object {
        const val FP = "fingerprint-a"
    }


    private fun tempFilesDir(): File = Files.createTempDirectory("files").toFile()

    @Test
    fun folderNameIncludesCodeAndUppercaseCrc() {
        assertEquals("POKEMON EMER [BPEE-1F1C08FB]", GameStorage.folderName("POKEMON EMER", "BPEE", 0x1F1C08FBL))
        assertEquals("PM_CRYSTAL [CGB-BYTE-0000ABCD]", GameStorage.folderName("PM_CRYSTAL", "CGB-BYTE", 0xABCDL))
    }

    @Test
    fun unknownGbMakerCodeIsLeftOut() {
        assertEquals("TETRIS [46DF91AD]", GameStorage.folderName("TETRIS", "DMG-????", 0x46DF91ADL))
        assertEquals("TETRIS [46DF91AD]", GameStorage.folderName("TETRIS", "", 0x46DF91ADL))
    }

    @Test
    fun sanitizesUnsafeTitles() {
        assertEquals("a b c", GameStorage.sanitizeTitle("a/b\\c"))
        assertEquals("hidden", GameStorage.sanitizeTitle("..hidden"))
        assertEquals("Game", GameStorage.sanitizeTitle("///"))
        assertEquals(48, GameStorage.sanitizeTitle("x".repeat(100)).length)
        assertEquals("Mario & Luigi (USA)", GameStorage.sanitizeTitle("Mario & Luigi (USA)"))
    }

    @Test
    fun pathsLiveUnderPlatformFolder() {
        val filesDir = tempFilesDir()
        val storage = GameStorage.forGame(filesDir, Platform.GBA, "POKEMON EMER", "BPEE", 0x1F1C08FBL)
        assertEquals(File(filesDir, "games/gba/POKEMON EMER [BPEE-1F1C08FB]"), storage.dir)
        assertEquals(File(storage.dir, "game.sav"), storage.saveFile)
        assertEquals(File(storage.dir, "states/slot3.ss"), storage.stateFile(3))
    }

    @Test
    fun forGameDoesNotCreateFoldersUntilAsked() {
        val filesDir = tempFilesDir()
        val storage = GameStorage.forGame(filesDir, Platform.GB, "TETRIS", "DMG-????", 1L)
        assertFalse(storage.dir.exists())
        storage.ensureDirs()
        assertTrue(storage.statesDir.isDirectory)
    }

    @Test
    fun findsExistingFolderByCrcEvenIfTitleDiffers() {
        val filesDir = tempFilesDir()
        val original = GameStorage.forGame(filesDir, Platform.GBA, "OLD TITLE", "BPEE", 0x1234L).ensureDirs()
        val again = GameStorage.forGame(filesDir, Platform.GBA, "NEW TITLE", "BPEE", 0x1234L)
        assertEquals(original.dir, again.dir)
    }

    @Test
    fun differentCrcOrPlatformGetsItsOwnFolder() {
        val filesDir = tempFilesDir()
        val a = GameStorage.forGame(filesDir, Platform.GBA, "GAME", "AAAA", 0x1L).ensureDirs()
        val b = GameStorage.forGame(filesDir, Platform.GBA, "GAME", "AAAA", 0x2L)
        val c = GameStorage.forGame(filesDir, Platform.GB, "GAME", "", 0x1L)
        assertFalse(a.dir == b.dir)
        assertFalse(a.dir == c.dir)
    }

    @Test
    fun crcSuffixMatchNeedsSeparator() {
        val filesDir = tempFilesDir()
        // A folder whose CRC merely ends with the same hex digits must not match.
        File(filesDir, "games/gba/GAME [X00001234]").mkdirs()
        val storage = GameStorage.forGame(filesDir, Platform.GBA, "GAME", "", 0x1234L)
        assertEquals("GAME [00001234]", storage.dir.name)
    }

    @Test
    fun packIdRoundTripsAndClears() {
        val storage = GameStorage.forGame(tempFilesDir(), Platform.GBA, "GAME", "AAAA", 0x1L)
        assertNull(storage.packId)
        storage.packId = "demo-pipeline"
        assertEquals("demo-pipeline", storage.packId)
        storage.packId = null
        assertNull(storage.packId)
    }

    @Test
    fun writeConsentIsKeptPerPackAndGame() {
        val filesDir = tempFilesDir()
        val emerald = GameStorage.forGame(filesDir, Platform.GBA, "POKEMON EMER", "BPEE", 0x1F1C08FBL)
        val ruby = GameStorage.forGame(filesDir, Platform.GBA, "POKEMON RUBY", "AXVE", 0x61641576L)
        assertNull(emerald.writeConsent("items", FP))

        emerald.setWriteConsent("items", true, FP)
        emerald.setWriteConsent("cheats", false, FP)

        assertEquals(true, emerald.writeConsent("items", FP))
        assertEquals(false, emerald.writeConsent("cheats", FP))
        assertNull(emerald.writeConsent("other", FP))
        assertNull(ruby.writeConsent("items", FP)) // per game

        // Survives a fresh lookup (it's on disk in the game's folder), and can change.
        val again = GameStorage.forGame(filesDir, Platform.GBA, "POKEMON EMER", "BPEE", 0x1F1C08FBL)
        assertEquals(true, again.writeConsent("items", FP))
        again.setWriteConsent("items", false, FP)
        assertEquals(false, emerald.writeConsent("items", FP))
        assertTrue(File(emerald.dir, "consent.json").exists())
    }

    @Test
    fun aChangedPackIsAskedAgain() {
        val storage = GameStorage.forGame(tempFilesDir(), Platform.GBA, "GAME", "AAAA", 0x1L)
        storage.setWriteConsent("items", true, FP)
        assertEquals(true, storage.writeConsent("items", FP))
        // Same id, different code (an update, or another pack claiming the id): not asked yet.
        assertNull(storage.writeConsent("items", "other-fingerprint"))
        storage.setWriteConsent("items", false, "other-fingerprint")
        assertEquals(false, storage.writeConsent("items", "other-fingerprint"))
        assertNull(storage.writeConsent("items", FP))
    }

    @Test
    fun answersFromBeforeFingerprintsAreAskedAgain() {
        val storage = GameStorage.forGame(tempFilesDir(), Platform.GBA, "GAME", "AAAA", 0x1L).ensureDirs()
        File(storage.dir, "consent.json").writeText("""{"memory-write":{"items":true}}""")
        assertNull(storage.writeConsent("items", FP))
    }

    @Test
    fun brokenConsentFileMeansNotAsked() {
        val storage = GameStorage.forGame(tempFilesDir(), Platform.GBA, "GAME", "AAAA", 0x1L).ensureDirs()
        File(storage.dir, "consent.json").writeText("{not json")
        assertNull(storage.writeConsent("items", FP))
        storage.setWriteConsent("items", true, FP)
        assertEquals(true, storage.writeConsent("items", FP))
    }

    @Test
    fun currentSlotDefaultsClampsAndPersists() {
        val files = tempFilesDir()
        val storage = GameStorage.forGame(files, Platform.GBA, "POKEMON", "BPEE", 0x1234L)
        assertEquals(1, storage.currentSlot)

        storage.currentSlot = 3
        assertEquals(3, GameStorage.forGame(files, Platform.GBA, "POKEMON", "BPEE", 0x1234L).currentSlot)

        storage.currentSlot = 12
        assertEquals(9, storage.currentSlot)

        File(storage.dir, "settings.json").writeText("""{"slot":"x"}""")
        assertEquals(1, storage.currentSlot)

        storage.settings.put("shader", "lcd")
        storage.currentSlot = 4
        assertEquals("lcd", storage.settings.getString("shader"))
        assertEquals(4, storage.currentSlot)
    }
}
