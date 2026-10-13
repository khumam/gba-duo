package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackStoreTest {

    private val manifestJson = """
        {"id":"demo-pipeline","name":"Demo","version":"1.0.0","author":"a",
         "formatVersion":1,"match":{"gameCode":["BPRE"],"crc32":[]},
         "tickHz":4,"layout":"layout.json","values":{}}
    """.trimIndent()

    @Test
    fun listsInstalledPacks() {
        val packsDir = createTempDir()
        File(packsDir, "demo-pipeline").mkdirs()
        File(packsDir, "demo-pipeline/manifest.json").writeText(manifestJson)

        val packs = PackStore(packsDir).list()

        assertEquals(1, packs.size)
        assertEquals("demo-pipeline", packs[0].id)
    }

    @Test
    fun skipsFoldersWithoutAValidManifest() {
        val packsDir = createTempDir()
        File(packsDir, "broken").mkdirs()
        File(packsDir, "broken/manifest.json").writeText("not json")
        File(packsDir, "good").mkdirs()
        File(packsDir, "good/manifest.json").writeText(manifestJson.replace("demo-pipeline", "good"))

        val packs = PackStore(packsDir).list()

        assertEquals(1, packs.size)
        assertEquals("good", packs[0].id)
    }

    @Test
    fun matchingFiltersByGameCode() {
        val packsDir = createTempDir()
        File(packsDir, "demo-pipeline").mkdirs()
        File(packsDir, "demo-pipeline/manifest.json").writeText(manifestJson)

        val store = PackStore(packsDir)
        assertEquals(1, store.matching("BPRE").size)
        assertTrue(store.matching("AAAA").isEmpty())
    }

    @Test
    fun byIdReturnsInstalledPackOrNull() {
        val packsDir = createTempDir()
        File(packsDir, "demo-pipeline").mkdirs()
        File(packsDir, "demo-pipeline/manifest.json").writeText(manifestJson)

        val store = PackStore(packsDir)
        assertEquals("demo-pipeline", store.byId("demo-pipeline")?.id)
        assertEquals(null, store.byId("missing"))
    }
}
