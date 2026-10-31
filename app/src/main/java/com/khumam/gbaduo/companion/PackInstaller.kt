package com.khumam.gbaduo.companion

import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Installs a pack zip: extract to a temp dir, validate manifest+layout+id,
 * then atomically rename into place. On any failure the temp dir is removed
 * and whatever was already installed under that id (if anything) is untouched.
 */
object PackInstaller {

    class InstallException(message: String) : Exception(message)

    fun install(zipFile: File, packsDir: File): PackManifest {
        packsDir.mkdirs()
        val tempDir = File(packsDir, ".tmp-${UUID.randomUUID()}")
        tempDir.mkdirs()
        try {
            extractZip(zipFile, tempDir)

            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.exists()) throw InstallException("Pack is missing manifest.json")
            val manifest = try {
                PackManifest.parse(manifestFile.readText())
            } catch (e: PackManifest.InvalidManifestException) {
                throw InstallException(e.message ?: "Invalid manifest.json")
            }
            if (!PackManifest.isValidId(manifest.id)) {
                throw InstallException("Invalid pack id '${manifest.id}'")
            }
            if (!File(tempDir, manifest.layoutFile).exists()) {
                throw InstallException("Pack is missing ${manifest.layoutFile}")
            }
            for (style in manifest.styleFiles) {
                if (!File(tempDir, style).isFile) throw InstallException("Pack is missing $style")
            }
            manifest.gameScriptFile?.let { gameScript ->
                if (!File(tempDir, gameScript).isFile) throw InstallException("Pack is missing $gameScript")
            }

            val finalDir = File(packsDir, manifest.id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tempDir.renameTo(finalDir)) {
                throw InstallException("Could not install pack '${manifest.id}'")
            }
            return manifest
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            throw if (e is InstallException) e else InstallException(e.message ?: "Invalid pack")
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        val destRoot = destDir.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                val resolved = outFile.canonicalPath
                if (resolved != destRoot && !resolved.startsWith(destRoot + File.separator)) {
                    throw InstallException("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
