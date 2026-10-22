package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PackFingerprintTest {

    private fun pack(): File = Files.createTempDirectory("pack").toFile().apply {
        File(this, "manifest.json").writeText("""{"id":"items"}""")
        File(this, "main.lua").writeText("function update() return {} end")
        File(this, "layout.json").writeText("{}")
        File(this, "assets/ui").mkdirs()
        File(this, "assets/ui/icon.png").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun sameFilesSameFingerprint() {
        assertEquals(PackFingerprint.of(pack()), PackFingerprint.of(pack()))
    }

    @Test
    fun codeChangesTheFingerprint() {
        val dir = pack()
        val before = PackFingerprint.of(dir)
        File(dir, "main.lua").appendText("\nmem.write8(0x02000000, 1)")
        assertNotEquals(before, PackFingerprint.of(dir))

        val withModule = PackFingerprint.of(dir)
        File(dir, "helpers.lua").writeText("return {}") // a new pack.require module
        assertNotEquals(withModule, PackFingerprint.of(dir))

        val withLayout = PackFingerprint.of(dir)
        File(dir, "layout.json").writeText("""{"type":"button","onClick":{"call":"give"}}""")
        assertNotEquals(withLayout, PackFingerprint.of(dir))
    }

    @Test
    fun renamingAFileChangesTheFingerprint() {
        val dir = pack()
        val before = PackFingerprint.of(dir)
        File(dir, "main.lua").renameTo(File(dir, "other.lua"))
        assertNotEquals(before, PackFingerprint.of(dir))
    }

    @Test
    fun assetsDontChangeTheFingerprint() {
        val dir = pack()
        val before = PackFingerprint.of(dir)
        File(dir, "assets/ui/icon.png").writeBytes(byteArrayOf(9, 9, 9, 9))
        File(dir, "assets/fonts").mkdirs()
        File(dir, "assets/fonts/new.ttf").writeBytes(byteArrayOf(0))
        assertEquals(before, PackFingerprint.of(dir))
    }
}
