package com.khumam.gbaduo.settings

import com.khumam.gbaduo.emu.InUse
import com.khumam.gbaduo.emu.ShaderChoice
import com.khumam.gbaduo.emu.ShaderLibrary
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.shaderlib.ShaderNode
import com.khumam.gbaduo.emu.shaderlib.ShaderTree
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderPageModelTest {

    private val tree = ShaderTree(listOf("bilinear.slangp", "crt/crt-geom.slangp", "crt/a.slangp", "crt/crt-effects/glow.slangp"))

    private fun library(vararg downloaded: String): ShaderLibrary {
        val root = Files.createTempDirectory("slang").toFile()
        for (path in downloaded) File(root, path).apply { parentFile.mkdirs(); writeText("x") }
        return ShaderLibrary(root)
    }

    private fun inUse(choice: ShaderChoice, source: InUse.Source = InUse.Source.GAME, missing: ShaderChoice.Library? = null) = InUse(choice, source, missing)
    private val off = inUse(ShaderChoice.OFF, InUse.Source.NONE)

    @Test
    fun homeShowsTheCardThenTheFourBuiltInsThenTheLibraryEntry() {
        val rows = ShaderPageModel.rows(null, tree, ShaderPageModel.ListState.READY, library(), off)
        assertTrue(rows.first() is ShaderRow.InUseCard)
        val builtins = rows.filterIsInstance<ShaderRow.Builtin>()
        assertEquals(ShaderPreset.entries.toList(), builtins.map { it.preset })
        assertEquals(listOf(ShaderPreset.OFF), builtins.filter { it.active }.map { it.preset })
        assertTrue(rows.last() is ShaderRow.LibraryEntry)
        assertTrue(rows.none { it is ShaderRow.Folder || it is ShaderRow.Preset })
    }

    @Test
    fun theActiveBuiltInIsTheOnlyOneMarked() {
        val rows = ShaderPageModel.rows(null, tree, ShaderPageModel.ListState.READY, library(), inUse(ShaderChoice.Builtin(ShaderPreset.LCD)))
        assertEquals(listOf(ShaderPreset.LCD), rows.filterIsInstance<ShaderRow.Builtin>().filter { it.active }.map { it.preset })
    }

    @Test
    fun aFolderListsItsNodesWithDownloadedAndActiveMarks() {
        val lib = library("crt/crt-geom.slangp")
        val rows = ShaderPageModel.rows("crt", tree, ShaderPageModel.ListState.READY, lib, inUse(ShaderChoice.Library("crt/crt-geom.slangp")))
        assertTrue(rows.first() is ShaderRow.InUseCard)
        assertTrue(rows.none { it is ShaderRow.Builtin })
        val folder = rows.filterIsInstance<ShaderRow.Folder>().single()
        assertEquals("crt/crt-effects", folder.node.path)
        val presets = rows.filterIsInstance<ShaderRow.Preset>().associateBy { it.node.name }
        assertEquals(listOf("a", "crt-geom"), presets.keys.sorted())
        assertTrue(presets["crt-geom"]!!.downloaded && presets["crt-geom"]!!.active)
        assertTrue(!presets["a"]!!.downloaded && !presets["a"]!!.active)
        // folders come before presets
        assertTrue(rows.indexOf(folder) < rows.indexOfFirst { it is ShaderRow.Preset })
    }

    @Test
    fun theLibraryRootListsTopLevelNodes() {
        val rows = ShaderPageModel.rows("", tree, ShaderPageModel.ListState.READY, library(), off)
        assertEquals(listOf("crt", "bilinear"), rows.filterIsInstance<ShaderRow.Folder>().map { it.node.name } + rows.filterIsInstance<ShaderRow.Preset>().map { it.node.name })
    }

    @Test
    fun withoutAListTheLibraryAreaIsOneMessageAndTheBuiltInsStayUsable() {
        val unavailable = ShaderPageModel.rows("", null, ShaderPageModel.ListState.UNAVAILABLE, library(), off)
        assertEquals(listOf<ShaderRow>(ShaderRow.InUseCard(off), ShaderRow.Message(ShaderPageModel.MessageKind.NO_LIST)), unavailable)
        val loading = ShaderPageModel.rows("", null, ShaderPageModel.ListState.LOADING, library(), off)
        assertEquals(ShaderRow.Message(ShaderPageModel.MessageKind.LOADING), loading.last())

        val home = ShaderPageModel.rows(null, null, ShaderPageModel.ListState.UNAVAILABLE, library(), off)
        assertEquals(ShaderPreset.entries.size, home.filterIsInstance<ShaderRow.Builtin>().size)
    }

    @Test
    fun theCardCarriesAMissingShaderSoThePageCanSayIt() {
        val gone = ShaderChoice.Library("crt/gone.slangp")
        val rows = ShaderPageModel.rows(null, tree, ShaderPageModel.ListState.READY, library(), inUse(ShaderChoice.OFF, InUse.Source.NONE, gone))
        val card = rows.first() as ShaderRow.InUseCard
        assertNotNull(card.inUse.missing)
        assertEquals(gone, card.inUse.missing)
    }

    @Test
    fun titlesFollowTheFolder() {
        assertEquals(null, ShaderPageModel.folderName(null))
        assertEquals("", ShaderPageModel.folderName(""))
        assertEquals("crt-effects", ShaderPageModel.folderName("crt/crt-effects"))
        assertEquals(ShaderNode.Folder("crt").name, ShaderPageModel.folderName("crt"))
    }
}
