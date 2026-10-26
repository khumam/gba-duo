package com.khumam.gbaduo.library.artwork

import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.storage.FileSyncDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArtworkFolderIndexTest {

    private val root: File = Files.createTempDirectory("artfolder").toFile()

    private fun touch(path: String) {
        File(root, path).apply { parentFile!!.mkdirs(); writeText("x") }
    }

    private fun match(
        fileName: String,
        platform: Platform = Platform.GBA,
        title: String = "",
        code: String = "",
        crc: Long = 0x12345678,
    ): String? {
        val index = ArtworkFolderIndex.build(FileSyncDir(root))
        return index.match(fileName, title, code, crc, platform)?.path
    }

    @Test
    fun flatFolderMatchesRomStem() {
        touch("Wario Land 4.png")
        assertEquals("Wario Land 4.png", match("Wario Land 4.gba"))
    }

    @Test
    fun matchIgnoresCaseOfNameAndExtension() {
        touch("WARIO LAND 4.PNG")
        assertEquals("WARIO LAND 4.PNG", match("wario land 4.gba"))
    }

    @Test
    fun esDeCoversBeatScreenshotsWhichAreNeverUsed() {
        touch("downloaded_media/gba/screenshots/Game.png")
        assertNull(match("Game.gba"))
        touch("downloaded_media/gba/covers/Game.png")
        assertEquals("downloaded_media/gba/covers/Game.png", match("Game.gba"))
    }

    @Test
    fun betterMediaFolderWins() {
        touch("misc/Game.png")
        touch("covers/Game.png")
        assertEquals("covers/Game.png", match("Game.gba"))
    }

    @Test
    fun retroArchThumbnailsMatchSanitisedName() {
        touch("thumbnails/Nintendo - Game Boy Advance/Named_Boxarts/Mario _ Luigi_ Superstar Saga (USA).png")
        assertEquals(
            "thumbnails/Nintendo - Game Boy Advance/Named_Boxarts/Mario _ Luigi_ Superstar Saga (USA).png",
            match("Mario & Luigi: Superstar Saga (USA).gba"),
        )
    }

    @Test
    fun otherPlatformFolderIsNotUsed() {
        touch("gba/covers/Tetris.png")
        assertNull(match("Tetris.gbc", Platform.GBC))
        assertEquals("gba/covers/Tetris.png", match("Tetris.gba", Platform.GBA))
    }

    @Test
    fun gameBoyFolderIsNotTakenForGameBoyAdvance() {
        touch("Nintendo - Game Boy/Named_Boxarts/Tetris.png")
        assertNull(match("Tetris.gba", Platform.GBA))
        assertEquals("Nintendo - Game Boy/Named_Boxarts/Tetris.png", match("Tetris.gb", Platform.GB))
    }

    @Test
    fun cleanedTitleMatchesWhenUnique() {
        touch("art/the legend of zelda - the minish cap.png")
        assertEquals("art/the legend of zelda - the minish cap.png",
            match("Zelda_ROM.gba", title = "The Legend of Zelda: The Minish Cap"))
    }

    @Test
    fun cleanedTitleWithTwoCandidatesMatchesNothing() {
        touch("a/Zelda Minish Cap.png")
        touch("b/zelda-minish-cap.jpg")
        assertNull(match("rom.gba", title = "Zelda: Minish Cap"))
    }

    @Test
    fun gameCodeAndCrcNamesMatch() {
        touch("codes/BPEE.png")
        touch("crc/12345678.jpg")
        assertEquals("codes/BPEE.png", match("x.gba", code = "bpee"))
        assertEquals("crc/12345678.jpg", match("y.gba", crc = 0x12345678))
    }

    @Test
    fun nonImagesAreIgnored() {
        touch("Game.txt")
        touch("Game.mp4")
        assertNull(match("Game.gba"))
    }

    @Test
    fun deepFoldersAreIgnored() {
        touch("a/b/c/d/e/Game.png")
        assertNull(match("Game.gba"))
        touch("a/b/c/d/Game.png")
        assertEquals("a/b/c/d/Game.png", match("Game.gba"))
    }

    @Test
    fun openReadsTheMatchedFile() {
        touch("covers/Game.png")
        val dir = FileSyncDir(root)
        val image = ArtworkFolderIndex.build(dir).match("Game.gba", "", "", 0, Platform.GBA)!!
        assertEquals("x", image.open(dir).use { String(it.readBytes()) })
    }

    @Test
    fun cocoonSuffixIsIgnoredAndIconIsTheCover() {
        val rom = "Legend of Zelda, The - The Minish Cap (Europe) (En,Fr,De,Es,It)"
        touch("downloaded_media/gba/icon/$rom" + "__cocoon_edit_68_4ddc.png")
        touch("downloaded_media/gba/hero/$rom" + "__cocoon_edit_68_955c.jpg")
        touch("downloaded_media/gba/logo/$rom" + "__cocoon_edit_68_0501.png")
        touch("downloaded_media/gba/screenshot_title/$rom" + "__cocoon_edit_68_c291.jpg")
        touch("downloaded_media/gba/screenshot_gameplay/$rom" + "__cocoon_edit_68_955b.jpg")
        assertEquals("downloaded_media/gba/icon/$rom" + "__cocoon_edit_68_4ddc.png", match("$rom.gba"))
    }

    @Test
    fun cocoonHeroIsUsedWhenThereIsNoIcon() {
        touch("downloaded_media/gba/hero/Game__cocoon_edit_1_ab.jpg")
        touch("downloaded_media/gba/logo/Game__cocoon_edit_1_cd.png")
        assertEquals("downloaded_media/gba/hero/Game__cocoon_edit_1_ab.jpg", match("Game.gba"))
    }

    @Test
    fun cocoonLogoAndScreenshotsAreNeverCovers() {
        touch("downloaded_media/gba/logo/Game__cocoon_edit_1_cd.png")
        touch("downloaded_media/gba/screenshot_title/Game__cocoon_edit_1_ef.jpg")
        assertNull(match("Game.gba"))
    }
}
