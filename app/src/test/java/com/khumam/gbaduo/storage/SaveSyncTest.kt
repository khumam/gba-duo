package com.khumam.gbaduo.storage

import com.khumam.gbaduo.library.Platform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

class SaveSyncTest {

    private val root = "content://tree/saves"
    private var now = 1_700_000_000_000L

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** Writes [text] to [dir]/[path], stamped [modified]. */
    private fun write(dir: File, path: String, text: String, modified: Long = now): File {
        val f = File(dir, path)
        f.parentFile.mkdirs()
        f.writeText(text)
        f.setLastModified(modified)
        return f
    }

    private fun read(dir: File, path: String): String? = File(dir, path).takeIf { it.exists() }?.readText()

    private fun sync(local: File, remote: SyncDir, direction: SaveSync.Direction = SaveSync.Direction.BOTH) =
        SaveSync(FileSyncDir(local), remote, root, clock = { now }).sync(direction)

    private fun sync(local: File, remote: File, direction: SaveSync.Direction = SaveSync.Direction.BOTH) =
        sync(local, FileSyncDir(remote), direction)

    @Test
    fun firstSyncCopiesEverythingOut() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "save-1")
        write(local, "states/slot1.ss", "state-1")
        write(local, "pack.json", "{\"packId\":\"p\"}")

        val result = sync(local, remote)

        assertTrue(result.ok)
        assertEquals("save-1", read(remote, "game.sav"))
        assertEquals("state-1", read(remote, "states/slot1.ss"))
        assertEquals("{\"packId\":\"p\"}", read(remote, "pack.json"))
        // The private record stays private.
        assertNull(read(remote, SaveSync.BASE_FILE))
        assertNotNull(read(local, SaveSync.BASE_FILE))
    }

    @Test
    fun onlyTrackedFilesTravel() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "save")
        write(local, "notes.txt", "private")
        write(local, "states/thumb.png", "x")

        sync(local, remote)

        assertNull(read(remote, "notes.txt"))
        assertNull(read(remote, "states/thumb.png"))
    }

    @Test
    fun newerSaveInTheFolderIsTakenIn() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "old")
        sync(local, remote)

        // Played on another device: only the folder's copy changed.
        write(remote, "game.sav", "from-other-device", modified = now + 60_000)
        val result = sync(local, remote)

        assertEquals(listOf("game.sav"), result.copiedIn)
        assertEquals("from-other-device", read(local, "game.sav"))
    }

    @Test
    fun localChangeIsCopiedOut() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "v1")
        sync(local, remote)

        write(local, "game.sav", "v2", modified = now + 1000)
        val result = sync(local, remote, SaveSync.Direction.OUT)

        assertEquals(listOf("game.sav"), result.copiedOut)
        assertEquals("v2", read(remote, "game.sav"))
    }

    @Test
    fun unchangedFilesAreLeftAlone() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "v1")
        sync(local, remote)

        val result = sync(local, remote)

        assertTrue(result.copiedIn.isEmpty() && result.copiedOut.isEmpty() && result.conflicts.isEmpty())
    }

    @Test
    fun whilePlayingTheFolderNeverOverwritesTheGamesCopy() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "playing")
        sync(local, remote)

        write(remote, "game.sav", "other-device", modified = now + 60_000)
        val result = sync(local, remote, SaveSync.Direction.OUT)

        assertTrue(result.copiedIn.isEmpty())
        assertEquals("playing", read(local, "game.sav"))
        assertEquals("other-device", read(remote, "game.sav"))
    }

    @Test
    fun bothChangedNewerWinsAndTheOtherIsKeptAsConflictCopy() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "base")
        sync(local, remote)

        write(local, "game.sav", "this-device", modified = now + 1_000)
        write(remote, "game.sav", "other-device", modified = now + 5_000) // newer
        val result = sync(local, remote)

        assertEquals(listOf("game.sav"), result.conflicts)
        assertEquals("other-device", read(local, "game.sav"))
        assertEquals("other-device", read(remote, "game.sav"))
        val conflict = remote.listFiles()!!.single { it.name.startsWith("game.sav.conflict-") }
        assertEquals("this-device", conflict.readText())
    }

    @Test
    fun bothChangedLocalNewerKeepsTheFoldersVersionAsConflictCopy() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "base")
        sync(local, remote)

        write(remote, "game.sav", "other-device", modified = now + 1_000)
        write(local, "game.sav", "this-device", modified = now + 5_000) // newer
        sync(local, remote)

        assertEquals("this-device", read(local, "game.sav"))
        assertEquals("this-device", read(remote, "game.sav"))
        val conflict = remote.listFiles()!!.single { it.name.startsWith("game.sav.conflict-") }
        assertEquals("other-device", conflict.readText())
    }

    @Test
    fun firstSyncWithDifferentSavesOnBothSidesIsAConflictNotAnOverwrite() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "this-device", modified = now)
        write(remote, "game.sav", "already-there", modified = now - 60_000)

        val result = sync(local, remote)

        assertEquals(listOf("game.sav"), result.conflicts)
        assertEquals("this-device", read(remote, "game.sav"))
        assertEquals("already-there", remote.listFiles()!!.single { it.name.startsWith("game.sav.conflict-") }.readText())
    }

    @Test
    fun firstSyncWithIdenticalSavesChangesNothing() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "same", modified = now)
        write(remote, "game.sav", "same", modified = now - 60_000)

        val result = sync(local, remote)

        assertTrue(result.conflicts.isEmpty() && result.copiedOut.isEmpty() && result.copiedIn.isEmpty())
        assertEquals(1, remote.listFiles()!!.count { it.name.startsWith("game.sav") })
    }

    /** A folder whose writes break after a few bytes, like an SD card pulled mid-copy. */
    private class BreakingDir(private val inner: FileSyncDir) : SyncDir by inner {
        override fun openWrite(name: String): OutputStream {
            val out = inner.openWrite(name)
            return object : OutputStream() {
                var written = 0
                override fun write(b: Int) {
                    if (++written > 3) throw IOException("card removed")
                    out.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    for (i in off until off + len) write(b[i].toInt())
                }
                override fun close() = out.close()
            }
        }
        override fun subdir(name: String, create: Boolean): SyncDir? = inner.subdir(name, create)?.let { BreakingDir(it) }
    }

    @Test
    fun interruptedCopyLeavesTheOldFileInPlace() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "old-save")
        sync(local, remote)

        write(local, "game.sav", "new-save-that-breaks", modified = now + 1000)
        val result = sync(local, BreakingDir(FileSyncDir(remote)), SaveSync.Direction.OUT)

        assertEquals(listOf("game.sav"), result.failed)
        assertEquals("old-save", read(remote, "game.sav"))
        assertFalse(File(remote, "game.sav.partial").exists())

        // Retried on the next sync, once the folder works again.
        val retry = sync(local, remote, SaveSync.Direction.OUT)
        assertEquals(listOf("game.sav"), retry.copiedOut)
        assertEquals("new-save-that-breaks", read(remote, "game.sav"))
    }

    /** A provider that can't rename (some cloud-backed folders). */
    private class NoRenameDir(private val inner: FileSyncDir) : SyncDir by inner {
        override fun rename(from: String, to: String) = false
        override fun subdir(name: String, create: Boolean): SyncDir? = inner.subdir(name, create)?.let { NoRenameDir(it) }
    }

    @Test
    fun foldersThatCantRenameStillGetUpdated() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "v1")
        write(local, "states/slot1.ss", "s1")
        assertTrue(sync(local, NoRenameDir(FileSyncDir(remote))).ok)

        write(local, "game.sav", "v2", modified = now + 1000)
        val result = sync(local, NoRenameDir(FileSyncDir(remote)), SaveSync.Direction.OUT)

        assertTrue(result.ok)
        assertEquals("v2", read(remote, "game.sav"))
        assertEquals("s1", read(remote, "states/slot1.ss"))
        assertTrue(remote.walk().none { it.name.endsWith(".partial") })
    }

    @Test
    fun copyCutOffBetweenStepsIsRepairedNextTime() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "v1")
        sync(local, remote)
        // Cut off after moving the old file aside, before the new one went in.
        File(remote, "game.sav").renameTo(File(remote, "game.sav.old"))
        write(remote, "game.sav.partial", "half")

        sync(local, remote, SaveSync.Direction.OUT)

        assertEquals("v1", read(remote, "game.sav"))
        assertFalse(File(remote, "game.sav.old").exists())
        assertFalse(File(remote, "game.sav.partial").exists())
    }

    @Test
    fun saveDeletedFromTheFolderIsPutBackNotDeletedHere() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "keep-me")
        sync(local, remote)

        File(remote, "game.sav").delete()
        sync(local, remote)

        assertEquals("keep-me", read(local, "game.sav"))
        assertEquals("keep-me", read(remote, "game.sav"))
    }

    @Test
    fun clearingThePackTravels() {
        val local = tempDir("local")
        val remote = tempDir("remote")
        write(local, "game.sav", "s")
        write(local, "pack.json", "{\"packId\":\"p\"}")
        sync(local, remote)

        File(local, "pack.json").delete()
        sync(local, remote)

        assertNull(read(remote, "pack.json"))
        assertNull(read(local, "pack.json"))
    }

    @Test
    fun recordForAnotherFolderIsIgnored() {
        val local = tempDir("local")
        val first = tempDir("first")
        val second = tempDir("second")
        write(local, "game.sav", "mine")
        SaveSync(FileSyncDir(local), FileSyncDir(first), "content://tree/first").sync(SaveSync.Direction.BOTH)

        // A new folder that already has a different save: a conflict, not "unchanged, skip".
        write(second, "game.sav", "theirs", modified = now - 60_000)
        val result = SaveSync(FileSyncDir(local), FileSyncDir(second), "content://tree/second", clock = { now })
            .sync(SaveSync.Direction.BOTH)

        assertEquals(listOf("game.sav"), result.conflicts)
    }

    // ---- the library ------------------------------------------------------------------

    @Test
    fun firstTimeMoveCopiesAndChecksEveryGame() {
        val files = tempDir("files")
        val folder = tempDir("folder")
        val emerald = GameStorage.forGame(files, Platform.GBA, "POKEMON EMER", "BPEE", 0x1F1C08FBL).ensureDirs()
        val tetris = GameStorage.forGame(files, Platform.GB, "TETRIS", "", 0x46DF91ADL).ensureDirs()
        write(emerald.dir, "game.sav", "emerald")
        write(emerald.dir, "states/slot2.ss", "emerald-state")
        write(tetris.dir, "game.sav", "tetris")

        val result = SaveLibrary.moveAll(File(files, "games"), FileSyncDir(folder), root)

        assertTrue(result.ok)
        assertEquals(2, result.games)
        assertEquals("emerald", read(folder, "gba/POKEMON EMER [BPEE-1F1C08FB]/game.sav"))
        assertEquals("emerald-state", read(folder, "gba/POKEMON EMER [BPEE-1F1C08FB]/states/slot2.ss"))
        assertEquals("tetris", read(folder, "gb/TETRIS [46DF91AD]/game.sav"))
        // The private copies are untouched.
        assertEquals("emerald", read(emerald.dir, "game.sav"))
    }

    @Test
    fun firstTimeMoveReportsGamesThatFailed() {
        val files = tempDir("files")
        val folder = tempDir("folder")
        val game = GameStorage.forGame(files, Platform.GBA, "GAME", "AXXE", 0x12345678L).ensureDirs()
        write(game.dir, "game.sav", "longer than three bytes")

        val result = SaveLibrary.moveAll(File(files, "games"), BreakingDir(FileSyncDir(folder)), root)

        assertFalse(result.ok)
        assertEquals(listOf("gba/GAME [AXXE-12345678]"), result.failed)
    }

    @Test
    fun gameIsFoundInTheFolderByChecksumWhateverItsTitle() {
        val files = tempDir("files")
        val folder = tempDir("folder")
        // Named differently by another device (or an older version).
        write(folder, "gba/Pokemon Emerald [BPEE-1F1C08FB]/game.sav", "from-folder", modified = now)
        val storage = GameStorage.forGame(files, Platform.GBA, "POKEMON EMER", "BPEE", 0x1F1C08FBL).ensureDirs()

        SaveLibrary.syncGame(storage, FileSyncDir(folder), root, SaveSync.Direction.BOTH)

        assertEquals("from-folder", read(storage.dir, "game.sav"))
        assertFalse(File(folder, "gba/POKEMON EMER [BPEE-1F1C08FB]").exists())
    }

    /** A save folder that's gone: nothing can be listed or created in it. */
    private object GoneDir : SyncDir {
        override fun entries() = emptyList<SyncEntry>()
        override fun subdir(name: String, create: Boolean): SyncDir? = null
        override fun openRead(name: String) = throw IOException("gone")
        override fun openWrite(name: String) = throw IOException("gone")
        override fun rename(from: String, to: String) = false
        override fun delete(name: String) = false
    }

    @Test
    fun missingFolderLeavesThePrivateCopyAlone() {
        val files = tempDir("files")
        val storage = GameStorage.forGame(files, Platform.GBA, "GAME", "AXXE", 0x12345678L).ensureDirs()
        write(storage.dir, "game.sav", "safe")

        val result = SaveLibrary.syncGame(storage, GoneDir, root, SaveSync.Direction.BOTH)

        assertNull(result) // the caller plays from the private copy and shows a notice
        assertEquals("safe", read(storage.dir, "game.sav"))
    }

    @Test
    fun sameContentsComparesBytes() {
        val a = tempDir("a")
        val b = tempDir("b")
        write(a, "game.sav", "abc")
        write(b, "game.sav", "abc")
        assertTrue(SaveSync.sameContents(FileSyncDir(a), FileSyncDir(b)))
        write(b, "game.sav", "abd")
        assertFalse(SaveSync.sameContents(FileSyncDir(a), FileSyncDir(b)))
        assertArrayEquals("abc".toByteArray(), File(a, "game.sav").readBytes())
    }

    @Test
    fun crcTagIsReadFromFolderNames() {
        assertEquals("1F1C08FB", GameStorage.crcTagOf("POKEMON EMER [BPEE-1F1C08FB]"))
        assertEquals("46DF91AD", GameStorage.crcTagOf("TETRIS [46DF91AD]"))
        assertNull(GameStorage.crcTagOf("random folder"))
        assertTrue(GameStorage.sameGame("A [BPEE-1F1C08FB]", "Other title [BPEE-1F1C08FB]"))
        assertFalse(GameStorage.sameGame("A [BPEE-1F1C08FB]", "A [BPEE-1F1C08FC]"))
    }
}
