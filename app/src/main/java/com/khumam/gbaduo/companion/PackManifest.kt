package com.khumam.gbaduo.companion

import org.json.JSONObject

data class ValueSpec(
    val addr: Long?,
    val ptr: Long?,
    val offset: Int,
    val type: String,
    val mask: Long?,
    val shift: Int,
)

data class PackManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val formatVersion: Int,
    val gameCodes: List<String>,
    val crc32s: List<Long>,
    val tickHz: Int,
    val layoutFile: String,
    val scriptFile: String?,
    val values: Map<String, ValueSpec>,
    /** CSS files (relative to the pack root), applied in order after the app's built-in styles. */
    val styleFiles: List<String> = emptyList(),
    /** What the pack may do beyond reading the game: see [PERMISSIONS]. */
    val permissions: Set<String> = emptySet(),
) {
    /** The pack's actions may change the game's RAM (`mem.write*`), once the player allows it. */
    val canWriteMemory: Boolean get() = MEMORY_WRITE in permissions

    /** [crc32] ignored (any match) when this pack declares no CRCs. */
    fun matches(gameCode: String, crc32: Long): Boolean =
        gameCode in gameCodes && (crc32s.isEmpty() || crc32 in crc32s)

    class InvalidManifestException(message: String) : Exception(message)

    companion object {
        /** 1: original format. 2: adds `styles` (CSS). 3: adds `permissions`. */
        const val SUPPORTED_FORMAT_VERSION = 3
        private const val STYLES_FORMAT_VERSION = 2
        private const val PERMISSIONS_FORMAT_VERSION = 3

        const val MEMORY_WRITE = "memory-write"

        /** Every permission a manifest may ask for. Anything else is refused, so a typo can't pass silently. */
        val PERMISSIONS = setOf(MEMORY_WRITE)
        private val ID_REGEX = Regex("^[a-z0-9-]{1,64}$")

        fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

        private fun hex(s: String): Long = s.removePrefix("0x").removePrefix("0X").toLong(16)

        fun parse(json: String): PackManifest {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw InvalidManifestException("Malformed JSON: ${e.message}")
            }

            val formatVersion = root.optInt("formatVersion", -1)
            if (formatVersion !in 1..SUPPORTED_FORMAT_VERSION) {
                throw InvalidManifestException("Unsupported formatVersion $formatVersion")
            }

            val id = root.optString("id", "")
            val match = root.optJSONObject("match") ?: throw InvalidManifestException("Missing 'match'")
            val gameCodes = match.optJSONArray("gameCode")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val crc32s = match.optJSONArray("crc32")?.let { arr ->
                (0 until arr.length()).map { hex(arr.getString(it)) }
            } ?: emptyList()

            val valuesJson = root.optJSONObject("values") ?: JSONObject()
            val values = valuesJson.keys().asSequence().associateWith { key ->
                val v = valuesJson.getJSONObject(key)
                ValueSpec(
                    addr = if (v.has("addr")) hex(v.getString("addr")) else null,
                    ptr = if (v.has("ptr")) hex(v.getString("ptr")) else null,
                    offset = v.optInt("offset", 0),
                    type = v.getString("type"),
                    mask = if (v.has("mask")) hex(v.getString("mask")) else null,
                    shift = v.optInt("shift", 0),
                )
            }

            val styleFiles = root.optJSONArray("styles")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            if (styleFiles.isNotEmpty() && formatVersion < STYLES_FORMAT_VERSION) {
                throw InvalidManifestException("'styles' needs formatVersion $STYLES_FORMAT_VERSION")
            }

            val permissionsJson = root.optJSONArray("permissions")
            val permissions = permissionsJson?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } ?: emptySet()
            if (permissionsJson != null && formatVersion < PERMISSIONS_FORMAT_VERSION) {
                throw InvalidManifestException("'permissions' needs formatVersion $PERMISSIONS_FORMAT_VERSION")
            }
            val unknown = permissions - PERMISSIONS
            if (unknown.isNotEmpty()) {
                throw InvalidManifestException("Unknown permission ${unknown.joinToString()}")
            }

            return PackManifest(
                id = id,
                name = root.optString("name", id),
                version = root.optString("version", "0.0.0"),
                author = root.optString("author", ""),
                formatVersion = formatVersion,
                gameCodes = gameCodes,
                crc32s = crc32s,
                tickHz = root.optInt("tickHz", 4).coerceIn(1, 30),
                layoutFile = root.optString("layout", "layout.json"),
                scriptFile = root.optString("script", "").ifBlank { null },
                values = values,
                styleFiles = styleFiles,
                permissions = permissions,
            )
        }
    }
}
