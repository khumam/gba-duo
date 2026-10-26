package com.khumam.gbaduo.library.artwork

import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.storage.SyncDir
import java.io.InputStream

/**
 * The images in an artwork folder another launcher filled (ES-DE, RetroArch, a flat folder
 * named after the ROMs...), indexed once so a whole library can be matched cheaply.
 * Read only. Pure over [SyncDir], so JVM tests use a plain folder.
 */
class ArtworkFolderIndex private constructor(private val images: List<Image>) {

    /** An image: the folders above it and its file name. */
    class Image(val dirs: List<String>, val fileName: String) {
        val path: String get() = (dirs + fileName).joinToString("/")
        val stem: String get() = ArtworkNames.imageStem(fileName)

        fun open(root: SyncDir): InputStream {
            var dir = root
            for (name in dirs) dir = dir.subdir(name, false) ?: throw java.io.FileNotFoundException(path)
            return dir.openRead(fileName)
        }
    }

    /**
     * The best image for a game: the ROM's own name, then its cleaned [title] (only when one
     * image has it), then game code, then CRC. Images in another platform's folder, or in a
     * media folder that isn't a cover (screenshots, logos...), are never used.
     */
    fun match(romFileName: String, title: String, gameCode: String, crc32: Long, platform: Platform): Image? {
        val usable = images.filter { eligible(it, platform) }
        for (name in ArtworkNames.exactCandidates(romFileName)) {
            usable.filter { it.stem.lowercase() == name }.bestOrNull()?.let { return it }
        }
        val normalized = ArtworkNames.normalize(title)
        if (normalized.isNotEmpty()) {
            usable.filter { ArtworkNames.normalize(it.stem) == normalized }.singleOrNull()?.let { return it }
        }
        if (gameCode.isNotBlank()) {
            usable.filter { it.stem.equals(gameCode, ignoreCase = true) }.bestOrNull()?.let { return it }
        }
        val crc = ArtworkNames.crcName(crc32)
        return usable.filter { it.stem.equals(crc, ignoreCase = true) }.bestOrNull()
    }

    private fun List<Image>.bestOrNull(): Image? =
        sortedWith(compareByDescending<Image> { rank(it) }.thenBy { it.path }).firstOrNull()

    private fun rank(image: Image): Int {
        val index = image.dirs.minOfOrNull { dir -> COVER_FOLDERS.indexOf(dir.lowercase()).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        return if (index == null || index == Int.MAX_VALUE) 0 else COVER_FOLDERS.size - index
    }

    private fun eligible(image: Image, platform: Platform): Boolean {
        val dirs = image.dirs.map { it.lowercase() }
        if (dirs.any { it in OTHER_MEDIA }) return false
        return dirs.none { dir -> PLATFORM_FOLDERS.any { (p, names) -> p != platform && dir in names } }
    }

    companion object {
        private const val MAX_DEPTH = 4
        private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        private val PLATFORM_FOLDERS = mapOf(
            Platform.GBA to setOf("gba", "game boy advance", "nintendo - game boy advance"),
            Platform.GBC to setOf("gbc", "game boy color", "nintendo - game boy color"),
            Platform.GB to setOf("gb", "game boy", "nintendo - game boy"),
        )

        /** Cover-like folders, best first. */
        private val COVER_FOLDERS = listOf("covers", "boxart", "box2dfront", "named_boxarts", "box", "grid", "icon", "hero", "images")

        private val OTHER_MEDIA = setOf(
            "screenshots", "titlescreens", "named_snaps", "named_titles", "marquees", "wheel",
            "logo", "logos", "screenshot", "screenshot_title", "screenshot_gameplay", "fanart", "videos", "manuals", "backcovers", "miximages", "physicalmedia", "3dboxes",
        )

        fun build(root: SyncDir): ArtworkFolderIndex {
            val found = mutableListOf<Image>()
            fun walk(dir: SyncDir, path: List<String>) {
                for (entry in dir.entries()) {
                    if (entry.isDir) {
                        if (path.size < MAX_DEPTH) dir.subdir(entry.name, false)?.let { walk(it, path + entry.name) }
                    } else if (entry.name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                        found += Image(path, entry.name)
                    }
                }
            }
            walk(root, emptyList())
            return ArtworkFolderIndex(found)
        }
    }
}
