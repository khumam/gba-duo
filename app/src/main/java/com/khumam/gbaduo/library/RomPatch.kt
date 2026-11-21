package com.khumam.gbaduo.library

import java.util.zip.CRC32

/**
 * Applies IPS, UPS and BPS patches (ROM hacks, translations) to a ROM's bytes. Pure, no Android deps.
 * UPS and BPS carry the CRC32 of the ROM they were made for, so a patch for another ROM is refused;
 * IPS carries nothing to check, so it is applied as is.
 */
object RomPatch {

    enum class Format { IPS, UPS, BPS }

    /** Why a patch couldn't be applied. */
    class PatchException(val reason: Reason, message: String) : Exception(message)

    enum class Reason { UNSUPPORTED, CORRUPT, WRONG_ROM }

    fun formatOf(fileName: String): Format? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "ips" -> Format.IPS
        "ups" -> Format.UPS
        "bps" -> Format.BPS
        else -> null
    }

    fun isPatchFileName(name: String) = formatOf(name) != null

    /** The patched ROM; throws [PatchException]. [patchName] picks the format by its extension, then the header is checked. */
    fun apply(rom: ByteArray, patch: ByteArray, patchName: String): ByteArray {
        val format = formatOf(patchName) ?: throw PatchException(Reason.UNSUPPORTED, "Not an IPS, UPS or BPS patch: $patchName")
        return try {
            when (format) {
                Format.IPS -> ips(rom, patch)
                Format.UPS -> ups(rom, patch)
                Format.BPS -> bps(rom, patch)
            }
        } catch (e: PatchException) {
            throw e
        } catch (e: IndexOutOfBoundsException) {
            throw PatchException(Reason.CORRUPT, "The patch is cut short or damaged")
        } catch (e: NegativeArraySizeException) {
            throw PatchException(Reason.CORRUPT, "The patch is damaged")
        }
    }

    private const val MAX_ROM = 64 * 1024 * 1024

    private fun crc(bytes: ByteArray, length: Int = bytes.size): Long = CRC32().also { it.update(bytes, 0, length) }.value

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun corrupt(message: String): Nothing = throw PatchException(Reason.CORRUPT, message)

    // ---- IPS ------------------------------------------------------------------------

    private fun ips(rom: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 8 || String(patch, 0, 5, Charsets.ISO_8859_1) != "PATCH") corrupt("Not an IPS patch")
        var out = rom.copyOf()
        var p = 5
        fun u8() = patch[p++].toInt() and 0xFF
        fun grow(size: Int) {
            if (size > MAX_ROM) corrupt("The patch makes a ROM too big")
            if (size > out.size) out = out.copyOf(size)
        }
        while (true) {
            if (p + 3 > patch.size) corrupt("The patch has no end marker")
            if (patch[p] == 'E'.code.toByte() && patch[p + 1] == 'O'.code.toByte() && patch[p + 2] == 'F'.code.toByte()) {
                p += 3
                // Some tools append the length to cut the ROM to.
                if (p + 3 <= patch.size) {
                    val cut = (u8() shl 16) or (u8() shl 8) or u8()
                    if (cut in 1 until out.size) out = out.copyOf(cut)
                }
                return out
            }
            val offset = (u8() shl 16) or (u8() shl 8) or u8()
            val size = (u8() shl 8) or u8()
            if (size == 0) {
                val run = (u8() shl 8) or u8()
                val value = patch[p++]
                grow(offset + run)
                out.fill(value, offset, offset + run)
            } else {
                if (p + size > patch.size) corrupt("The patch is cut short")
                grow(offset + size)
                patch.copyInto(out, offset, p, p + size)
                p += size
            }
        }
    }

    // ---- UPS / BPS shared -----------------------------------------------------------

    /** byuu's variable-length number, shared by UPS and BPS. */
    private class Reader(val data: ByteArray, var pos: Int, val end: Int) {
        fun byte(): Int {
            if (pos >= end) corrupt("The patch is cut short")
            return data[pos++].toInt() and 0xFF
        }

        fun number(): Long {
            var value = 0L
            var shift = 1L
            while (true) {
                val x = byte()
                value += (x and 0x7F) * shift
                if (x and 0x80 != 0) return value
                shift = shift shl 7
                value += shift
                if (shift > (1L shl 56)) corrupt("The patch has a bad number")
            }
        }
    }

    // ---- UPS ------------------------------------------------------------------------

    private fun ups(rom: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 4 + 12 || String(patch, 0, 4, Charsets.ISO_8859_1) != "UPS1") corrupt("Not a UPS patch")
        val footer = patch.size - 12
        if (crc(patch, patch.size - 4) != le32(patch, patch.size - 4)) corrupt("The patch is damaged (checksum)")
        val reader = Reader(patch, 4, footer)
        val sourceSize = reader.number()
        val targetSize = reader.number()
        val sourceCrc = le32(patch, footer)
        val targetCrc = le32(patch, footer + 4)
        // UPS undoes itself too: applied to its own result it gives the original back.
        val romCrc = crc(rom)
        val outSize = when {
            romCrc == sourceCrc && rom.size.toLong() == sourceSize -> targetSize
            romCrc == targetCrc && rom.size.toLong() == targetSize -> sourceSize
            else -> throw PatchException(Reason.WRONG_ROM, "This patch is for a different ROM")
        }
        if (outSize > MAX_ROM) corrupt("The patch makes a ROM too big")
        val out = rom.copyOf(outSize.toInt())
        var at = 0L
        while (reader.pos < footer) {
            at += reader.number()
            while (true) {
                val x = reader.byte()
                if (x == 0) { at++; break }
                if (at < out.size) out[at.toInt()] = (out[at.toInt()].toInt() xor x).toByte()
                at++
            }
        }
        val expected = if (romCrc == sourceCrc) targetCrc else sourceCrc
        if (crc(out) != expected) corrupt("The patched ROM doesn't match what the patch expects")
        return out
    }

    // ---- BPS ------------------------------------------------------------------------

    private fun bps(rom: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 4 + 12 || String(patch, 0, 4, Charsets.ISO_8859_1) != "BPS1") corrupt("Not a BPS patch")
        val footer = patch.size - 12
        if (crc(patch, patch.size - 4) != le32(patch, patch.size - 4)) corrupt("The patch is damaged (checksum)")
        val reader = Reader(patch, 4, footer)
        val sourceSize = reader.number()
        val targetSize = reader.number()
        val metadata = reader.number()
        reader.pos += metadata.toInt()
        if (rom.size.toLong() != sourceSize || crc(rom) != le32(patch, footer)) {
            throw PatchException(Reason.WRONG_ROM, "This patch is for a different ROM")
        }
        if (targetSize > MAX_ROM) corrupt("The patch makes a ROM too big")
        val out = ByteArray(targetSize.toInt())
        var o = 0
        var sourceOffset = 0L
        var targetOffset = 0L
        while (reader.pos < footer) {
            val data = reader.number()
            val length = (data shr 2).toInt() + 1
            if (o + length > out.size) corrupt("The patch writes past the end of the ROM")
            when ((data and 3).toInt()) {
                0 -> for (i in 0 until length) { out[o] = rom[o]; o++ }                       // SourceRead
                1 -> for (i in 0 until length) out[o++] = reader.byte().toByte()             // TargetRead
                2 -> {                                                                        // SourceCopy
                    val d = reader.number()
                    sourceOffset += if (d and 1L != 0L) -(d shr 1) else d shr 1
                    for (i in 0 until length) out[o++] = rom[(sourceOffset++).toInt()]
                }
                else -> {                                                                     // TargetCopy
                    val d = reader.number()
                    targetOffset += if (d and 1L != 0L) -(d shr 1) else d shr 1
                    for (i in 0 until length) out[o++] = out[(targetOffset++).toInt()]
                }
            }
        }
        if (o != out.size || crc(out) != le32(patch, footer + 4)) corrupt("The patched ROM doesn't match what the patch expects")
        return out
    }
}
