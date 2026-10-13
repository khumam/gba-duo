package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PackAssignmentsTest {

    @Test
    fun missingFileReturnsNoAssignment() {
        val file = File.createTempFile("assignments", ".json").apply { delete() }
        val assignments = PackAssignments(file)
        assertNull(assignments.get("game.gba"))
    }

    @Test
    fun setThenGetRoundTrips() {
        val file = File.createTempFile("assignments", ".json")
        val assignments = PackAssignments(file)
        assignments.set("pokefirered.gba", "demo-pipeline")
        assertEquals("demo-pipeline", assignments.get("pokefirered.gba"))
    }

    @Test
    fun persistsAcrossInstances() {
        val file = File.createTempFile("assignments", ".json")
        PackAssignments(file).set("game.gba", "pack-a")
        val reloaded = PackAssignments(file)
        assertEquals("pack-a", reloaded.get("game.gba"))
    }

    @Test
    fun settingNoneRemovesAssignment() {
        val file = File.createTempFile("assignments", ".json")
        val assignments = PackAssignments(file)
        assignments.set("game.gba", "pack-a")
        assignments.set("game.gba", null)
        assertNull(assignments.get("game.gba"))
    }
}
