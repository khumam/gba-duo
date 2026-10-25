package com.khumam.gbaduo.library.artwork

import org.json.JSONObject
import java.io.File

/** Where a game's cached artwork came from. [source] is `folder` or `steamgriddb`; [ref] a path or image id. */
data class ArtworkMeta(val source: String, val ref: String, val at: Long)

/**
 * The artwork cache: `<root>/<platform>/<CRC8>.img` plus `<root>/index.json`. Keyed by
 * platform and ROM CRC32, like game folders. [generation] changes on every change, so
 * views know when to reload. Pure, no Android deps; callers hand it ready-to-store bytes.
 */
class ArtworkStore(private val root: File) {

    private val indexFile = File(root, "index.json")

    private fun read(): JSONObject = runCatching { JSONObject(indexFile.readText()) }.getOrNull() ?: JSONObject()

    private fun write(json: JSONObject) {
        root.mkdirs()
        writeAtomically(indexFile, json.toString().toByteArray())
    }

    private fun key(platform: String, crc32: Long) = "$platform/${ArtworkNames.crcName(crc32)}"

    private fun path(platform: String, crc32: Long) = File(File(root, platform), "${ArtworkNames.crcName(crc32)}.img")

    fun meta(platform: String, crc32: Long): ArtworkMeta? {
        val entry = read().optJSONObject("items")?.optJSONObject(key(platform, crc32)) ?: return null
        return ArtworkMeta(entry.optString("source"), entry.optString("ref"), entry.optLong("at"))
    }

    /** The stored image, or null when there is none. */
    fun file(platform: String, crc32: Long): File? = path(platform, crc32).takeIf { it.isFile && meta(platform, crc32) != null }

    fun put(platform: String, crc32: Long, bytes: ByteArray, source: String, ref: String, now: Long = System.currentTimeMillis()) {
        writeAtomically(path(platform, crc32), bytes)
        val json = read()
        val items = json.optJSONObject("items") ?: JSONObject().also { json.put("items", it) }
        items.put(key(platform, crc32), JSONObject().put("source", source).put("ref", ref).put("at", now))
        json.put("generation", json.optLong("generation") + 1)
        write(json)
    }

    fun remove(platform: String, crc32: Long) {
        path(platform, crc32).delete()
        val json = read()
        json.optJSONObject("items")?.remove(key(platform, crc32))
        json.put("generation", json.optLong("generation") + 1)
        write(json)
    }

    fun removeAll() {
        root.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
        val json = JSONObject().put("generation", read().optLong("generation") + 1)
        write(json)
    }

    fun count(): Int = read().optJSONObject("items")?.length() ?: 0

    fun generation(): Long = read().optLong("generation")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
