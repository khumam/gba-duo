package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class RomIndexTest {

    private fun tempIndex() = RomIndex(File.createTempFile("rom_index", ".json").apply { delete() })

    @Test
    fun missingEntryIsNull() {
        assertNull(tempIndex().crc32("content://rom", 10, 1))
    }

    @Test
    fun cachedCrcIsReturnedWhileFileUnchanged() {
        val index = tempIndex()
        index.put("content://rom", 10, 1, 0xDEADBEEFL)
        assertEquals(0xDEADBEEFL, index.crc32("content://rom", 10, 1))
    }

    @Test
    fun changedSizeOrTimeInvalidates() {
        val index = tempIndex()
        index.put("content://rom", 10, 1, 123L)
        assertNull(index.crc32("content://rom", 11, 1))
        assertNull(index.crc32("content://rom", 10, 2))
    }

    @Test
    fun streamAndArrayCrcAgree() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        assertEquals(RomIndex.crc32Of(bytes), RomIndex.crc32Of(ByteArrayInputStream(bytes)))
        assertEquals(0xCBF43926L, RomIndex.crc32Of("123456789".toByteArray()))
    }
}
