package com.khumam.gbaduo.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomArchiveTest {

    private fun gbaRom(code: String): ByteArray {
        val rom = ByteArray(0x400) { (it * 7).toByte() }
        "ZIPPED GAME".toByteArray().copyInto(rom, 0xA0)
        code.toByteArray().copyInto(rom, 0xAC)
        rom[0xB2] = 0x96.toByte()
        return rom
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun listsZipsAlongsideRoms() {
        assertTrue(RomArchive.isLibraryFileName("game.zip"))
        assertTrue(RomArchive.isLibraryFileName("GAME.ZIP"))
        assertTrue(RomArchive.isLibraryFileName("game.gba"))
        assertTrue(RomArchive.isLibraryFileName("game.gbc"))
        assertFalse(RomArchive.isLibraryFileName("game.7z"))
        assertFalse(RomArchive.isLibraryFileName("notes.txt"))
        assertTrue(RomArchive.isArchiveName("game.zip"))
        assertFalse(RomArchive.isArchiveName("game.gba"))
    }

    @Test
    fun skipsMacMetadataFilesNextToRoms() {
        // macOS leaves a 4 KB "._name" AppleDouble file beside each file it copies to FAT/exFAT cards.
        assertFalse(RomArchive.isLibraryFileName("._Metroid Fusion (USA).zip"))
        assertFalse(RomArchive.isLibraryFileName("._Pokemon - Emerald Version (USA, Europe).gba"))
        assertTrue(RomArchive.isLibraryFileName("Metroid Fusion (USA).zip"))
    }

    @Test
    fun extractsTheRomInsideAZip() {
        val rom = gbaRom("AMTE")
        val archive = zip("readme.txt" to "hi".toByteArray(), "Metroid Fusion.gba" to rom)

        val extracted = RomArchive.readRom("Metroid Fusion.zip", ByteArrayInputStream(archive))!!

        assertEquals("Metroid Fusion.gba", extracted.fileName)
        assertArrayEquals(rom, extracted.bytes)
        // Same bytes, so the same CRC32 (and saves, game folder, pack match) as the bare ROM.
        assertEquals(RomIndex.crc32Of(rom), RomIndex.crc32Of(extracted.bytes))
    }

    @Test
    fun skipsMacResourceForksAndFolders() {
        val rom = gbaRom("BPRE")
        val archive = zip(
            "__MACOSX/FireRed/._FireRed.gba" to ByteArray(0x200),
            "FireRed/" to ByteArray(0),
            "FireRed/._FireRed.gba" to ByteArray(0x200),
            "FireRed/FireRed.gba" to rom,
        )

        val extracted = RomArchive.readRom("FireRed.zip", ByteArrayInputStream(archive))!!

        assertEquals("FireRed.gba", extracted.fileName)
        assertArrayEquals(rom, extracted.bytes)
    }

    @Test
    fun readsJustTheHeaderOfTheRomInsideAZip() {
        val rom = gbaRom("BZME")
        val archive = zip("minish.gba" to rom)

        val header = RomArchive.readHeader("minish.zip", ByteArrayInputStream(archive))!!

        assertEquals("minish.gba", header.fileName)
        assertEquals(RomHeader.SIZE, header.bytes.size)
        assertEquals("BZME", RomHeader.parse(header.fileName, header.bytes).gameCode)
    }

    /** STORED entries carry their size in the local header, like most zips made by zip tools. */
    private fun storedZip(name: String, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = RomIndex.crc32Of(bytes)
            })
            zip.write(bytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun readsARomWhoseSizeTheZipRecords() {
        val rom = gbaRom("AMTE")

        val extracted = RomArchive.readRom("fusion.zip", ByteArrayInputStream(storedZip("fusion.gba", rom)))!!

        assertArrayEquals(rom, extracted.bytes)
    }

    @Test
    fun readSizedHandlesShortAndLongStreams() {
        val data = ByteArray(1000) { it.toByte() }
        // Exactly the promised size, delivered in small chunks.
        assertArrayEquals(data, RomArchive.readSized(ChunkedStream(data, 7), data.size))
        // Fewer bytes than promised: what's there, not a padded array.
        assertArrayEquals(data.copyOf(400), RomArchive.readSized(ChunkedStream(data.copyOf(400), 64), data.size))
        // More bytes than promised: everything, not a truncated ROM.
        assertArrayEquals(data, RomArchive.readSized(ChunkedStream(data, 64), 600))
    }

    /** Hands out at most [chunk] bytes per read, like a network or zip stream. */
    private class ChunkedStream(private val data: ByteArray, private val chunk: Int) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            data.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }

    @Test
    fun zipWithoutARomGivesNothing() {
        val archive = zip("readme.txt" to "no rom here".toByteArray())

        assertNull(RomArchive.readRom("empty.zip", ByteArrayInputStream(archive)))
        assertNull(RomArchive.readHeader("empty.zip", ByteArrayInputStream(archive)))
    }

    @Test
    fun bareRomsPassStraightThrough() {
        val rom = gbaRom("BPEE")

        val extracted = RomArchive.readRom("emerald.gba", ByteArrayInputStream(rom))!!
        val header = RomArchive.readHeader("emerald.gba", ByteArrayInputStream(rom))!!

        assertEquals("emerald.gba", extracted.fileName)
        assertArrayEquals(rom, extracted.bytes)
        assertEquals("BPEE", RomHeader.parse(header.fileName, header.bytes).gameCode)
    }

    @Test
    fun crcOfAZipIsTheRomsNotTheArchives() {
        val rom = gbaRom("AMTE")
        val archive = zip("fusion.gba" to rom)

        assertEquals(RomIndex.crc32Of(rom), RomArchive.crc32("fusion.zip", ByteArrayInputStream(archive)))
        assertEquals(RomIndex.crc32Of(rom), RomArchive.crc32("fusion.gba", ByteArrayInputStream(rom)))
    }
}
