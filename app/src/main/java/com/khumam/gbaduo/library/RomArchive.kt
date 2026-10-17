package com.khumam.gbaduo.library

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lets the library and player treat a `.zip` holding a ROM like the ROM itself.
 * The core gets ROM bytes (it's built without mGBA's own zip support), so the
 * ROM is unpacked here; its CRC32 is the ROM's, not the archive's, so a zipped
 * game keeps the saves, game folder and pack match of the bare one. Pure, no
 * Android deps.
 */
object RomArchive {

    /** A ROM file's name (the entry's, for an archive) and some or all of its bytes. */
    class Rom(val fileName: String, val bytes: ByteArray)

    fun isArchiveName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "zip"

    /**
     * What the library lists: bare ROMs and zips (which may turn out to hold none).
     * Not the 4 KB "._name" AppleDouble files macOS leaves beside each file it
     * copies to a FAT/exFAT card.
     */
    fun isLibraryFileName(name: String): Boolean =
        !name.startsWith("._") && (RomHeader.isRomFileName(name) || isArchiveName(name))

    /**
     * The whole ROM: [input] itself for a bare ROM, the first ROM entry for a zip; null if a zip has none.
     * When the zip records the ROM's size (most do), it's read straight into an array that size:
     * a zip stream can't report how much is left, so readBytes() would grow its buffer by doubling
     * and briefly hold two to three times the ROM.
     */
    fun readRom(fileName: String, input: InputStream): Rom? =
        withRom(fileName, input) { name, rom, size ->
            Rom(name, if (size in 0..MAX_ROM_SIZE) readSized(rom, size.toInt()) else rom.readBytes())
        }

    /**
     * Reads [input] into an array of [size] bytes. Fewer bytes than that gives what was
     * there; more (a size the zip got wrong) gives everything rather than a cut-off ROM.
     */
    fun readSized(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) return buffer.copyOf(read)
            read += n
        }
        val extra = input.readBytes()
        return if (extra.isEmpty()) buffer else buffer + extra
    }

    /** Largest ROM the GBA can map (32 MB); bigger "sizes" aren't trusted. */
    private const val MAX_ROM_SIZE = 32L * 1024 * 1024

    /** The first [RomHeader.SIZE] bytes of the ROM (fewer if it's shorter); null if a zip has none. */
    fun readHeader(fileName: String, input: InputStream): Rom? =
        withRom(fileName, input) { name, rom, _ -> Rom(name, readUpTo(rom, RomHeader.SIZE)) }

    /** CRC32 of the ROM (inside the zip, for an archive); null if a zip has none. */
    fun crc32(fileName: String, input: InputStream): Long? =
        withRom(fileName, input) { _, rom, _ -> RomIndex.crc32Of(rom) }

    /**
     * Runs [block] with the ROM's name, a stream positioned at its first byte, and its
     * size if known (-1 when a zip didn't record it; always -1 for a bare ROM stream).
     */
    private fun <T> withRom(fileName: String, input: InputStream, block: (String, InputStream, Long) -> T): T? {
        if (!isArchiveName(fileName)) return block(fileName, input, -1L)
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: return null
            val name = entry.name.substringAfterLast('/')
            // Skip folders and the "._name" resource forks macOS adds when zipping.
            if (entry.isDirectory || name.startsWith("._") || entry.name.startsWith("__MACOSX/")) continue
            if (RomHeader.isRomFileName(name)) return block(name, zip, entry.size)
        }
    }

    private fun readUpTo(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }
}
