package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderListTest {

    @Test
    fun nothingStoredIsEmpty() {
        assertEquals(emptyList<String>(), FolderList.decode(null, null))
    }

    @Test
    fun migratesLegacySingleFolder() {
        assertEquals(listOf("content://tree/a"), FolderList.decode(null, "content://tree/a"))
    }

    @Test
    fun storedListWinsOverLegacy() {
        val json = FolderList.encode(listOf("content://tree/b"))
        assertEquals(listOf("content://tree/b"), FolderList.decode(json, "content://tree/a"))
    }

    @Test
    fun roundTripsAndDeduplicates() {
        val json = FolderList.encode(listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), FolderList.decode(json, null))
    }

    @Test
    fun malformedJsonIsEmpty() {
        assertEquals(emptyList<String>(), FolderList.decode("not json", "legacy"))
    }

    @Test
    fun addSkipsDuplicatesAndRemoveDrops() {
        val folders = FolderList.add(FolderList.add(emptyList(), "a"), "a")
        assertEquals(listOf("a"), folders)
        assertEquals(listOf("a", "b"), FolderList.add(folders, "b"))
        assertEquals(listOf("b"), FolderList.remove(listOf("a", "b"), "a"))
    }
}
