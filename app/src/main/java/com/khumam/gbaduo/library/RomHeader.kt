package com.khumam.gbaduo.library

enum class Platform(val id: String, val label: String) {
    GBA("gba", "GBA"),
    GB("gb", "GB"),
    GBC("gbc", "GBC"),
    ;

    companion object {
        fun byId(id: String?): Platform? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the library needs from a ROM's cartridge header. Pure, no Android deps.
 * The header decides the platform; the file extension is only a fallback for
 * headers too short or damaged to recognise.
 */
data class RomHeader(val platform: Platform, val title: String, val gameCode: String) {

    companion object {
        /** Enough bytes to cover both the GBA header (0x00–0xBF) and the GB header (0x100–0x14F). */
        const val SIZE = 0x150

        private val EXTENSIONS = setOf("gba", "gb", "gbc")

        /** First 4 bytes of the Nintendo logo every GB/GBC cartridge carries at 0x104. */
        private val GB_LOGO_START = byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66)
        private const val GBA_FIXED_OFFSET = 0xB2
        private const val GBA_FIXED_VALUE = 0x96.toByte()

        fun isRomFileName(name: String): Boolean = extensionOf(name) in EXTENSIONS

        fun parse(fileName: String, header: ByteArray): RomHeader {
            val platform = detectPlatform(fileName, header)
            return if (platform == Platform.GBA) parseGba(fileName, header) else parseGb(fileName, header, platform)
        }

        private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

        private fun detectPlatform(fileName: String, header: ByteArray): Platform {
            if (header.size >= SIZE && GB_LOGO_START.indices.all { header[0x104 + it] == GB_LOGO_START[it] }) {
                return if (isCgb(header[0x143])) Platform.GBC else Platform.GB
            }
            if (header.size > GBA_FIXED_OFFSET && header[GBA_FIXED_OFFSET] == GBA_FIXED_VALUE) {
                return Platform.GBA
            }
            return when (extensionOf(fileName)) {
                "gbc" -> Platform.GBC
                "gb" -> Platform.GB
                else -> Platform.GBA
            }
        }

        private fun isCgb(flag: Byte): Boolean {
            val value = flag.toInt() and 0xFF
            return value == 0x80 || value == 0xC0
        }

        private fun parseGba(fileName: String, header: ByteArray): RomHeader {
            val title = ascii(header, 0xA0, 12)
            val code = ascii(header, 0xAC, 4)
            return RomHeader(Platform.GBA, title.ifBlank { fileName }, code)
        }

        /** Mirrors mGBA's GBGetGameCode so library codes match `NativeCore.gameCode()` (and pack manifests). */
        private fun parseGb(fileName: String, header: ByteArray, platform: Platform): RomHeader {
            if (header.size < SIZE) return RomHeader(platform, fileName, "")
            val cgbFlag = header[0x143].toInt() and 0xFF
            val newLicensee = (header[0x14B].toInt() and 0xFF) == 0x33
            val titleEnd = when {
                newLicensee -> 0x13F // 0x13F–0x142 is the manufacturer code
                cgbFlag and 0x80 != 0 -> 0x143
                else -> 0x144
            }
            val title = ascii(header, 0x134, titleEnd - 0x134)
            val prefix = if (cgbFlag == 0xC0) "CGB-" else "DMG-"
            val maker = if (newLicensee) ascii(header, 0x13F, 4).padEnd(4, '?') else "????"
            return RomHeader(platform, title.ifBlank { fileName }, prefix + maker)
        }

        /** Printable ASCII up to the first NUL, trimmed. */
        private fun ascii(header: ByteArray, offset: Int, length: Int): String {
            if (offset >= header.size) return ""
            val end = minOf(offset + length, header.size)
            val chars = StringBuilder()
            for (i in offset until end) {
                val c = header[i].toInt() and 0xFF
                if (c == 0) break
                if (c in 0x20..0x7E) chars.append(c.toChar())
            }
            return chars.toString().trim()
        }
    }
}
