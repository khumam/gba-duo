package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeMemory(private val bytes: Map<Long, Int>) : MemoryReader {
    override fun u8(addr: Long): Int = bytes[addr] ?: 0
    override fun u16(addr: Long): Int = (u8(addr) or (u8(addr + 1) shl 8))
    override fun u32(addr: Long): Long =
        (u8(addr).toLong() or (u8(addr + 1).toLong() shl 8) or
            (u8(addr + 2).toLong() shl 16) or (u8(addr + 3).toLong() shl 24))
}

class ValuesReaderTest {

    @Test
    fun readsU8FromDirectAddress() {
        val memory = FakeMemory(mapOf(0x1000L to 0x42))
        val spec = ValueSpec(addr = 0x1000L, ptr = null, offset = 0, type = "u8", mask = null, shift = 0)
        assertEquals(0x42L, ValuesReader.read(spec, memory))
    }

    @Test
    fun readsU32LittleEndian() {
        val memory = FakeMemory(mapOf(0x2000L to 0x78, 0x2001L to 0x56, 0x2002L to 0x34, 0x2003L to 0x12))
        val spec = ValueSpec(addr = 0x2000L, ptr = null, offset = 0, type = "u32", mask = null, shift = 0)
        assertEquals(0x12345678L, ValuesReader.read(spec, memory))
    }

    @Test
    fun readsThroughPointerPlusOffset() {
        // pointer at 0x3000 holds 0x02000000; value lives at (pointer + 4)
        val memory = FakeMemory(
            mapOf(
                0x3000L to 0x00, 0x3001L to 0x00, 0x3002L to 0x00, 0x3003L to 0x02,
                0x02000004L to 0x7B,
            ),
        )
        val spec = ValueSpec(addr = null, ptr = 0x3000L, offset = 4, type = "u8", mask = null, shift = 0)
        assertEquals(0x7BL, ValuesReader.read(spec, memory))
    }

    @Test
    fun appliesMaskThenShift() {
        val memory = FakeMemory(mapOf(0x4000L to 0xF0, 0x4001L to 0x0F))
        // u16 = 0x0FF0; mask 0x0FFF -> 0x0FF0; shift 4 -> 0x0FF
        val spec = ValueSpec(addr = 0x4000L, ptr = null, offset = 0, type = "u16", mask = 0x0FFFL, shift = 4)
        assertEquals(0x0FFL, ValuesReader.read(spec, memory))
    }

    @Test
    fun signExtendsSignedTypes() {
        val memory = FakeMemory(mapOf(0x5000L to 0xFF)) // -1 as s8
        val spec = ValueSpec(addr = 0x5000L, ptr = null, offset = 0, type = "s8", mask = null, shift = 0)
        assertEquals(-1L, ValuesReader.read(spec, memory))
    }

    @Test
    fun readsAllValuesIntoAMap() {
        val memory = FakeMemory(mapOf(0x1000L to 7))
        val values = mapOf("hp" to ValueSpec(addr = 0x1000L, ptr = null, offset = 0, type = "u8", mask = null, shift = 0))
        assertEquals(mapOf("hp" to 7L), ValuesReader.readAll(values, memory))
    }
}
