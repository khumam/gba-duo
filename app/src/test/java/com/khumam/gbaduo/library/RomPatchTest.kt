package com.khumam.gbaduo.library

import com.khumam.gbaduo.library.RomPatch.PatchException
import com.khumam.gbaduo.library.RomPatch.Reason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class RomPatchTest {
    private val rom = ByteArray(64) { (it * 3).toByte() }

    private fun crc(b: ByteArray) = CRC32().also { it.update(b) }.value

    private fun ByteArrayOutputStream.le32(v: Long) { for (i in 0..3) write(((v shr (8 * i)) and 0xFF).toInt()) }

    /** byuu's number encoding, the inverse of what the reader does. */
    private fun ByteArrayOutputStream.number(value: Long) {
        var v = value
        while (true) {
            val x = (v and 0x7F).toInt()
            v = v shr 7
            if (v == 0L) { write(x or 0x80); return }
            write(x)
            v--
        }
    }

    private fun withCrc(body: ByteArrayOutputStream): ByteArray {
        val bytes = body.toByteArray()
        return bytes + ByteArrayOutputStream().also { it.le32(crc(bytes)) }.toByteArray()
    }

    private fun ips(vararg records: ByteArray): ByteArray =
        "PATCH".toByteArray() + records.fold(ByteArray(0)) { a, b -> a + b } + "EOF".toByteArray()

    private fun ipsRecord(offset: Int, data: ByteArray) =
        byteArrayOf((offset shr 16).toByte(), (offset shr 8).toByte(), offset.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    private fun ipsRle(offset: Int, run: Int, value: Int) =
        byteArrayOf((offset shr 16).toByte(), (offset shr 8).toByte(), offset.toByte(), 0, 0, (run shr 8).toByte(), run.toByte(), value.toByte())

    @Test fun ipsOverwritesAndFillsAndGrows() {
        val patch = ips(ipsRecord(2, byteArrayOf(9, 9)), ipsRle(10, 4, 0x77), ipsRecord(70, byteArrayOf(5)))
        val out = RomPatch.apply(rom, patch, "x.ips")
        assertEquals(71, out.size)
        assertEquals(9, out[2].toInt()); assertEquals(9, out[3].toInt())
        assertEquals(0x77, out[13].toInt())
        assertEquals(5, out[70].toInt())
        assertEquals(rom[20], out[20])
        assertEquals(0, out[65].toInt())   // the gap reads as zeros
    }

    @Test fun ipsIgnoresTheInputArray() {
        RomPatch.apply(rom, ips(ipsRecord(0, byteArrayOf(1))), "x.ips")
        assertEquals(0, rom[0].toInt())
    }

    @Test fun upsPatchesAndUndoes() {
        val target = rom.copyOf().also { it[5] = 0x55; it[6] = 0x66 }
        val body = ByteArrayOutputStream()
        body.write("UPS1".toByteArray())
        body.number(rom.size.toLong()); body.number(target.size.toLong())
        body.number(5)
        body.write((rom[5].toInt() xor 0x55)); body.write((rom[6].toInt() xor 0x66)); body.write(0)
        body.le32(crc(rom)); body.le32(crc(target))
        val patch = withCrc(body)

        assertArrayEquals(target, RomPatch.apply(rom, patch, "x.ups"))
        assertArrayEquals(rom, RomPatch.apply(target, patch, "x.ups"))
    }

    @Test fun bpsPatches() {
        // Source-read the first 10 bytes, then write 3 new ones, then copy the last 4 source bytes back from offset 20.
        val target = rom.copyOf(10) + byteArrayOf(1, 2, 3) + rom.copyOfRange(20, 24)
        val body = ByteArrayOutputStream()
        body.write("BPS1".toByteArray())
        body.number(rom.size.toLong()); body.number(target.size.toLong()); body.number(0)
        body.number(((10 - 1) shl 2).toLong())                   // SourceRead 10
        body.number(((3 - 1) shl 2 or 1).toLong()); body.write(byteArrayOf(1, 2, 3))   // TargetRead 3
        body.number(((4 - 1) shl 2 or 2).toLong()); body.number(20L shl 1)             // SourceCopy 4 from +20
        body.le32(crc(rom)); body.le32(crc(target))
        val patch = withCrc(body)

        assertArrayEquals(target, RomPatch.apply(rom, patch, "x.bps"))
    }

    @Test fun bpsForAnotherRomIsRefused() {
        val body = ByteArrayOutputStream()
        body.write("BPS1".toByteArray())
        body.number(rom.size.toLong()); body.number(1); body.number(0)
        body.number(0)
        body.le32(crc(rom) xor 1); body.le32(0)
        try {
            RomPatch.apply(rom, withCrc(body), "x.bps")
            fail("expected a refusal")
        } catch (e: PatchException) {
            assertEquals(Reason.WRONG_ROM, e.reason)
        }
    }

    @Test fun damagedAndUnknownPatchesAreRefused() {
        val good = ips(ipsRecord(0, byteArrayOf(1)))
        for ((bytes, name, reason) in listOf(
            Triple(good.copyOf(7), "x.ips", Reason.CORRUPT),
            Triple(byteArrayOf(1, 2, 3), "x.bps", Reason.CORRUPT),
            Triple(good, "x.txt", Reason.UNSUPPORTED),
        )) {
            try {
                RomPatch.apply(rom, bytes, name)
                fail("expected a refusal for $name")
            } catch (e: PatchException) {
                assertEquals(reason, e.reason)
            }
        }
    }

    @Test fun recognisesPatchNames() {
        assertTrue(RomPatch.isPatchFileName("Hack.IPS"))
        assertEquals(RomPatch.Format.BPS, RomPatch.formatOf("a.b.bps"))
        assertEquals(null, RomPatch.formatOf("a.zip"))
    }
}
