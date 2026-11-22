package com.khumam.gbaduo.companion

import java.io.File

/** Scans each subfolder of `packsDir` for a `manifest.json`. An unparseable one is skipped, not fatal. */
class PackStore(private val packsDir: File) {

    fun list(): List<PackManifest> {
        val dirs = packsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return@mapNotNull null
            runCatching { PackManifest.parse(manifestFile.readText()) }.getOrNull()
        }
    }

    fun matching(gameCode: String, crc32: Long = 0L): List<PackManifest> =
        list().filter { it.matches(gameCode, crc32) }

    /** Packs mapped for another region of this game; see [PackManifest.matchesOtherRegion]. */
    fun otherRegion(gameCode: String): List<PackManifest> =
        list().filter { it.matchesOtherRegion(gameCode) }

    fun byId(id: String): PackManifest? = list().find { it.id == id }

    /**
     * The picture in the pack's own `overview` folder, if it ships one (a pack imported from a file has no catalog
     * entry to take a picture from). The first image by name; hidden files, such as macOS's `._` copies, are skipped.
     */
    fun overviewImage(manifest: PackManifest): File? =
        File(packsDir, "${manifest.id}/overview").listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in OVERVIEW_TYPES
        }?.minByOrNull { it.name.lowercase() }

    fun layoutFile(manifest: PackManifest): File = File(packsDir, "${manifest.id}/${manifest.layoutFile}")

    fun assetFile(manifest: PackManifest, relativePath: String): File =
        File(packsDir, "${manifest.id}/assets/$relativePath")

    private companion object {
        val OVERVIEW_TYPES = setOf("png", "jpg", "jpeg", "webp")
    }
}
