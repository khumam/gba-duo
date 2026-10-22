package com.khumam.gbaduo.companion

import java.io.File
import java.security.MessageDigest

/**
 * What a pack *does*, as a SHA-256: every file outside `assets/` (its scripts, manifest,
 * layout and styles), by path and content. The player's "let this pack change the game"
 * answer is kept with it, so a different or updated pack under the same id is asked
 * about again. Images and fonts (`assets/`) can't change what a tap does, so a new
 * picture doesn't ask again.
 */
object PackFingerprint {

    fun of(packDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val root = packDir.canonicalFile
        val files = root.walkTopDown()
            .onEnter { it == root || it.relativeTo(root).invariantSeparatorsPath != "assets" }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
        for ((path, file) in files) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.length().toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
