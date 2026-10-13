package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackInstallerTest {

    private fun zipOf(dir: File, entries: Map<String, String>): File {
        val zip = File(dir, "pack.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    private val validManifest = """
        {"id":"demo-pipeline","name":"Demo","version":"1.0.0","author":"a",
         "formatVersion":1,"match":{"gameCode":["BPRE"],"crc32":[]},
         "tickHz":4,"layout":"layout.json","values":{}}
    """.trimIndent()
    private val validLayout = """{"type":"text","text":"hi"}"""

    @Test
    fun installsAValidPack() {
        val tmp = createTempDir()
        val packsDir = File(tmp, "packs").apply { mkdirs() }
        val zip = zipOf(tmp, mapOf("manifest.json" to validManifest, "layout.json" to validLayout))

        val manifest = PackInstaller.install(zip, packsDir)

        assertEquals("demo-pipeline", manifest.id)
        assertTrue(File(packsDir, "demo-pipeline/manifest.json").exists())
        assertTrue(File(packsDir, "demo-pipeline/layout.json").exists())
    }

    @Test(expected = PackInstaller.InstallException::class)
    fun rejectsZipSlip() {
        val tmp = createTempDir()
        val packsDir = File(tmp, "packs").apply { mkdirs() }
        val zip = zipOf(
            tmp,
            mapOf(
                "manifest.json" to validManifest,
                "layout.json" to validLayout,
                "../../../escaped.txt" to "pwned",
            ),
        )

        PackInstaller.install(zip, packsDir)
    }

    @Test(expected = PackInstaller.InstallException::class)
    fun rejectsInvalidId() {
        val tmp = createTempDir()
        val packsDir = File(tmp, "packs").apply { mkdirs() }
        val badManifest = validManifest.replace("\"demo-pipeline\"", "\"../saves\"")
        val zip = zipOf(tmp, mapOf("manifest.json" to badManifest, "layout.json" to validLayout))

        PackInstaller.install(zip, packsDir)
    }

    @Test
    fun failedInstallLeavesExistingSameIdPackUntouched() {
        val tmp = createTempDir()
        val packsDir = File(tmp, "packs").apply { mkdirs() }
        val goodZip = zipOf(tmp, mapOf("manifest.json" to validManifest, "layout.json" to validLayout))
        PackInstaller.install(goodZip, packsDir)
        val originalLayout = File(packsDir, "demo-pipeline/layout.json").readText()

        // Same id, but missing layout.json entirely -> should fail validation and not touch the existing pack.
        val brokenZip = File(tmp, "broken.zip")
        ZipOutputStream(brokenZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(validManifest.toByteArray())
            zos.closeEntry()
        }

        runCatching { PackInstaller.install(brokenZip, packsDir) }

        assertTrue(File(packsDir, "demo-pipeline/manifest.json").exists())
        assertEquals(originalLayout, File(packsDir, "demo-pipeline/layout.json").readText())
    }
}
