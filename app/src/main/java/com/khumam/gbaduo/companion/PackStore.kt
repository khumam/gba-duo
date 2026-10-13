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

    fun byId(id: String): PackManifest? = list().find { it.id == id }

    fun layoutFile(manifest: PackManifest): File = File(packsDir, "${manifest.id}/${manifest.layoutFile}")

    fun assetFile(manifest: PackManifest, relativePath: String): File =
        File(packsDir, "${manifest.id}/assets/$relativePath")
}
