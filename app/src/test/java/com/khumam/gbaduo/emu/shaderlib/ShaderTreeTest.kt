package com.khumam.gbaduo.emu.shaderlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderTreeTest {

    private val presets = listOf(
        "nearest.slangp", "bilinear.slangp", "crt/crt-geom.slangp", "crt/Zebra.slangp", "crt/crt-effects/glow.slangp",
        "pixel-art-scaling/sharp-bilinear.slangp", "handheld/lcd-grid-v2.slangp",
    )
    private val tree = ShaderTree(presets)

    private fun names(nodes: List<ShaderNode>) = nodes.map { (if (it is ShaderNode.Folder) "[" else "") + it.name }

    @Test
    fun rootListsFoldersFirstThenPresetsEachSortedIgnoringCase() {
        assertEquals(listOf("[crt", "[handheld", "[pixel-art-scaling", "bilinear", "nearest"), names(tree.children("")))
    }

    @Test
    fun aFolderListsItsSubfoldersAndPresetsWithRepoPaths() {
        val crt = tree.children("crt")
        assertEquals(listOf("[crt-effects", "crt-geom", "Zebra"), names(crt))
        assertEquals("crt/crt-effects", (crt[0] as ShaderNode.Folder).path)
        assertEquals("crt/crt-geom.slangp", (crt[1] as ShaderNode.Preset).path)
    }

    @Test
    fun foldersWithNoPresetsAreNotListed() {
        // `shaders`, `include`, `spec` and `textures` hold no .slangp, so they never appear (only their parents' presets do)
        assertTrue(tree.children("crt").none { it.name == "shaders" })
        assertTrue(tree.children("").none { it.name == "include" || it.name == "spec" })
        assertEquals(emptyList<ShaderNode>(), tree.children("crt/shaders"))
        assertEquals(emptyList<ShaderNode>(), tree.children("nowhere"))
    }

    @Test
    fun aDeepPresetMakesEveryFolderAboveItAppear() {
        val deep = ShaderTree(listOf("a/b/c/x.slangp"))
        assertEquals(listOf("[a"), names(deep.children("")))
        assertEquals(listOf("[b"), names(deep.children("a")))
        assertEquals(listOf("[c"), names(deep.children("a/b")))
        assertEquals(listOf("x"), names(deep.children("a/b/c")))
    }
}
