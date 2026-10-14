package com.khumam.gbaduo.library

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32

/**
 * Caches each ROM's CRC32 (keyed by URI, invalidated when size or modified time
 * change) so the library hashes a ROM at most once. Pure, no Android deps.
 */
class RomIndex(private val file: File) {

    private fun load(): JSONObject =
        if (file.exists() && file.length() > 0) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()

    fun crc32(key: String, size: Long, lastModified: Long): Long? {
        val entry = load().optJSONObject(key) ?: return null
        if (entry.optLong("size", -1) != size || entry.optLong("modified", -1) != lastModified) return null
        return entry.optLong("crc32", -1).takeIf { it >= 0 }
    }

    fun put(key: String, size: Long, lastModified: Long, crc32: Long) {
        val json = load()
        json.put(key, JSONObject().put("size", size).put("modified", lastModified).put("crc32", crc32))
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    companion object {
        fun crc32Of(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

        fun crc32Of(input: InputStream): Long {
            val crc = CRC32()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
            return crc.value
        }
    }
}
