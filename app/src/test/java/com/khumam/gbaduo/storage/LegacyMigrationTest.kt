package com.khumam.gbaduo.storage

import com.khumam.gbaduo.companion.PackAssignments
import com.khumam.gbaduo.library.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacyMigrationTest {

    private val crc = 0x1F1C08FBL

    private fun setup(): Pair<File, GameStorage> {
        val filesDir = Files.createTempDirectory("files").toFile()
        return filesDir to GameStorage.forGame(filesDir, Platform.GBA, "POKEMON EMER", "BPEE", crc)
    }

    @Test
    fun movesSaveStatesAndPackAssignment() {
        val (filesDir, storage) = setup()
        File(filesDir, "saves").mkdirs()
        File(filesDir, "saves/Pokemon Emerald.sav").writeText("sram")
        File(filesDir, "states/1f1c08fb").mkdirs()
        File(filesDir, "states/1f1c08fb/slot1.ss").writeText("s1")
        File(filesDir, "states/1f1c08fb/slot9.ss").writeText("s9")
        PackAssignments(File(filesDir, "pack_assignments.json")).set("Pokemon Emerald.gba", "emerald-pack")

        val result = LegacyMigration.migrate(filesDir, "Pokemon Emerald.gba", crc, storage)

        assertTrue(result.movedSave)
        assertEquals(2, result.movedStates)
        assertTrue(result.movedPack)
        assertEquals("sram", storage.saveFile.readText())
        assertEquals("s1", storage.stateFile(1).readText())
        assertEquals("s9", storage.stateFile(9).readText())
        assertEquals("emerald-pack", storage.packId)
        assertFalse(File(filesDir, "saves/Pokemon Emerald.sav").exists())
        assertFalse(File(filesDir, "states/1f1c08fb").exists())
        assertNull(PackAssignments(File(filesDir, "pack_assignments.json")).get("Pokemon Emerald.gba"))
    }

    @Test
    fun legacySaveNameMatchesOldRomEntryRule() {
        assertEquals("My Game (v1.1).sav", LegacyMigration.legacySaveName("My Game (v1.1).gba"))
        assertEquals("noext.sav", LegacyMigration.legacySaveName("noext"))
        // Old saves for upper-case extensions used the same rule.
        assertEquals("Game.sav", LegacyMigration.legacySaveName("Game.GBA"))
    }

    @Test
    fun isIdempotentAndHandlesNothingToMigrate() {
        val (filesDir, storage) = setup()
        val first = LegacyMigration.migrate(filesDir, "x.gba", crc, storage)
        val second = LegacyMigration.migrate(filesDir, "x.gba", crc, storage)
        assertEquals(LegacyMigration.Result(false, 0, false), first)
        assertEquals(first, second)
        assertFalse(storage.dir.exists())
    }

    @Test
    fun neverOverwritesExistingNewLayoutFiles() {
        val (filesDir, storage) = setup()
        storage.ensureDirs()
        storage.saveFile.writeText("new sram")
        storage.stateFile(1).writeText("new s1")
        storage.packId = "new-pack"
        File(filesDir, "saves").mkdirs()
        File(filesDir, "saves/x.sav").writeText("old sram")
        File(filesDir, "states/1f1c08fb").mkdirs()
        File(filesDir, "states/1f1c08fb/slot1.ss").writeText("old s1")
        PackAssignments(File(filesDir, "pack_assignments.json")).set("x.gba", "old-pack")

        LegacyMigration.migrate(filesDir, "x.gba", crc, storage)

        assertEquals("new sram", storage.saveFile.readText())
        assertEquals("new s1", storage.stateFile(1).readText())
        assertEquals("new-pack", storage.packId)
        // Unmoved legacy data stays where it was rather than being lost.
        assertEquals("old sram", File(filesDir, "saves/x.sav").readText())
        assertEquals("old s1", File(filesDir, "states/1f1c08fb/slot1.ss").readText())
    }

    @Test
    fun otherRomsAssignmentsAreKept() {
        val (filesDir, storage) = setup()
        val assignments = PackAssignments(File(filesDir, "pack_assignments.json"))
        assignments.set("x.gba", "pack-x")
        assignments.set("y.gba", "pack-y")
        LegacyMigration.migrate(filesDir, "x.gba", crc, storage)
        assertEquals("pack-y", PackAssignments(File(filesDir, "pack_assignments.json")).get("y.gba"))
    }

    @Test
    fun moveFileRefusesToReplaceTarget() {
        val dir = Files.createTempDirectory("move").toFile()
        val source = File(dir, "a").apply { writeText("a") }
        val target = File(dir, "b").apply { writeText("b") }
        assertFalse(LegacyMigration.moveFile(source, target))
        assertEquals("a", source.readText())
        assertEquals("b", target.readText())
    }
}
