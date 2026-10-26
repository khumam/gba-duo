package com.khumam.gbaduo.library.artwork

import java.text.Normalizer

/** Names an artwork image might carry for a game. Pure, no Android deps. */
object ArtworkNames {
    private val RETROARCH_FORBIDDEN = Regex("""[&*/:`<>?\\|"]""")

    /** The file name without its extension. */
    fun stem(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** An image's name without its extension and without the `__cocoon_...` suffix Cocoon appends to its downloads. */
    fun imageStem(fileName: String): String = stem(fileName).substringBefore("__cocoon_")

    /** RetroArch's thumbnail naming: characters it can't put in a file name become `_`. */
    fun retroArch(name: String): String = name.replace(RETROARCH_FORBIDDEN, "_")

    /** Lowercase letters and digits only, accents dropped, so punctuation and spacing differences don't matter. */
    fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    fun crcName(crc32: Long): String = "%08X".format(crc32 and 0xFFFFFFFFL)

    /** Lowercase image names that match the ROM file exactly, best first (the second only if it differs). */
    fun exactCandidates(romFileName: String): List<String> {
        val stem = stem(romFileName).lowercase()
        return listOf(stem, retroArch(stem)).distinct()
    }
}
