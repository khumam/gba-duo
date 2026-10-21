package com.khumam.gbaduo.companion.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CssParserTest {

    @Test
    fun parsesRulesAndSkipsComments() {
        val sheet = CssParser.parse(
            """
            /* header */
            .card, #main { padding: 8px; background: #123 /* inline */; }
            text { color: red }
            """.trimIndent(),
        )
        assertEquals(2, sheet.rules.size)
        assertEquals(2, sheet.rules[0].selectors.size)
        assertEquals(listOf("padding", "background"), sheet.rules[0].declarations.map { it.property })
        assertTrue(sheet.warnings.isEmpty())
    }

    @Test
    fun dropsUnknownPropertiesInvalidValuesAndSelectorsWithWarnings() {
        val sheet = CssParser.parse(
            """
            .a { colr: red; color: notacolor; width: 10px; }
            a + b { color: red }
            @media (min-width: 100px) { .a { color: red } }
            .b { color: blue }
            """.trimIndent(),
        )
        assertEquals(listOf("width"), sheet.rules[0].declarations.map { it.property })
        assertEquals(3, sheet.rules.size) // `.a`, the @media `.a` and `.b`; the sibling rule is skipped
        assertEquals(1, sheet.rules[1].media.size)
        assertTrue(sheet.rules[1].media.single().matches(200f, 100f))
        assertEquals(3, sheet.warnings.size)
    }

    @Test
    fun varValuesAreKeptForLaterValidation() {
        val sheet = CssParser.parse(":root { --accent: #f00 } .a { color: var(--accent) }")
        assertEquals("var(--accent)", sheet.rules[1].declarations.single().value)
        assertEquals("--accent", sheet.rules[0].declarations.single().property)
    }

    @Test
    fun importantIsIgnoredAndSemicolonsInUrlsSurvive() {
        val decls = CssParser.parseDeclarations("color: red !important; background-image: url('a;b.png')", null)
        assertEquals("red", decls[0].value)
        assertEquals("url('a;b.png')", decls[1].value)
    }

    @Test
    fun recoversFromMissingBrace() {
        val sheet = CssParser.parse(".a { color: red } .b { color: blue")
        assertEquals(1, sheet.rules.size)
        assertEquals(1, sheet.warnings.size)
    }

    @Test
    fun fontFaceResolvesRelativeToStylesheetInsidePack() {
        val pack = Files.createTempDirectory("pack").toFile()
        val styles = File(pack, "styles").apply { mkdirs() }
        val sheet = CssParser.parse(
            """
            @font-face { font-family: "Pixel"; src: url(../fonts/pixel.ttf) format("truetype"); font-weight: bold; }
            @font-face { font-family: Evil; src: url(../../outside.ttf); }
            """.trimIndent(),
            baseDir = styles,
            packDir = pack,
        )
        val face = sheet.fontFaces.single()
        assertEquals("Pixel", face.family)
        assertEquals(File(pack, "fonts/pixel.ttf").canonicalFile, face.file)
        assertEquals(700, face.weight)
        assertEquals(1, sheet.warnings.size)
    }

    @Test
    fun resolveUrlRejectsEscapesAndSchemes() {
        val pack = Files.createTempDirectory("pack").toFile()
        assertEquals(File(pack, "a/b.png").canonicalFile, CssParser.resolveUrl("a/b.png", pack, pack))
        assertNull(CssParser.resolveUrl("../x.png", pack, pack))
        assertNull(CssParser.resolveUrl("/etc/passwd", pack, pack))
        assertNull(CssParser.resolveUrl("https://example.com/x.png", pack, pack))
    }
}
