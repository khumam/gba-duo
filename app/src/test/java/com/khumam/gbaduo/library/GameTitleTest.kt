package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GameTitleTest {

    @Test
    fun movesTheArticleAndJoinsTheSubtitle() {
        assertEquals(
            "The Legend of Zelda: The Minish Cap",
            GameTitle.fromFileName("Legend of Zelda, The - The Minish Cap (Europe) (En,Fr,De,Es,It).gba"),
        )
    }

    @Test
    fun dropsRegionAndDumpTags() {
        assertEquals("Metroid Fusion", GameTitle.fromFileName("Metroid Fusion (USA).zip"))
        assertEquals("Tetris", GameTitle.fromFileName("Tetris (World) (Rev 1) [!].gb"))
        assertEquals("Pokemon: Fire Red Version", GameTitle.fromFileName("Pokemon - Fire Red Version (USA, Europe) (Rev 1).gba"))
    }

    @Test
    fun keepsPlainNamesAndUnderscores() {
        assertEquals("my homebrew", GameTitle.fromFileName("my_homebrew.gba"))
        assertEquals("Harvest Moon: Friends of Mineral Town", GameTitle.fromFileName("Harvest Moon - Friends of Mineral Town.gba"))
    }

    @Test
    fun fallsBackToTheHeaderTitle() {
        assertEquals("POKEMON FIRE", GameTitle.display("(USA).gba", "POKEMON FIRE"))
        assertEquals("Kirby", GameTitle.display("Kirby.gba", "KIRBY DREAM"))
    }
}
