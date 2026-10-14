package com.khumam.gbaduo.storage

import com.khumam.gbaduo.library.Platform
import org.json.JSONObject
import java.io.File

/**
 * One folder per game holding everything that belongs to it:
 * `files/games/<platform>/<Title> [<code>-]<CRC32>/{game.sav, states/slotN.ss, pack.json}`.
 *
 * Identity is platform + ROM CRC32, so moving/renaming the ROM or keeping it in
 * two library folders never splits its save. The title is only there to keep the
 * folder readable: lookups match on the CRC suffix. Pure, no Android deps.
 */
class GameStorage private constructor(val dir: File) {

    val saveFile: File get() = File(dir, "game.sav")
    val statesDir: File get() = File(dir, "states")
    private val packFile: File get() = File(dir, "pack.json")

    fun stateFile(slot: Int): File = File(statesDir, "slot$slot.ss")

    /** Creates the game folder (and `states/`) if missing. Call before anything writes into it. */
    fun ensureDirs(): GameStorage {
        statesDir.mkdirs()
        return this
    }

    /** The companion pack assigned to this game, or null. Setting null clears it. */
    var packId: String?
        get() = runCatching { JSONObject(packFile.readText()).optString("packId", "").ifBlank { null } }.getOrNull()
        set(value) {
            if (value == null) {
                packFile.delete()
            } else {
                ensureDirs()
                packFile.writeText(JSONObject().put("packId", value).toString())
            }
        }

    companion object {
        private const val MAX_TITLE_LENGTH = 48

        /** Finds this game's existing folder by CRC (even if its title part differs), or names a new one. Doesn't create it. */
        fun forGame(filesDir: File, platform: Platform, title: String, gameCode: String, crc32: Long): GameStorage {
            val platformDir = File(File(filesDir, "games"), platform.id)
            val tag = crcTag(crc32)
            val existing = platformDir.listFiles()?.firstOrNull { it.isDirectory && hasCrcTag(it.name, tag) }
            return GameStorage(existing ?: File(platformDir, folderName(title, gameCode, crc32)))
        }

        fun folderName(title: String, gameCode: String, crc32: Long): String {
            val code = gameCode.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
            val prefix = if (code != null) "$code-" else ""
            return "${sanitizeTitle(title)} [$prefix${crcTag(crc32)}]"
        }

        /** Keeps the title readable but safe on any filesystem (also if saves are ever copied to SD/FAT). */
        fun sanitizeTitle(title: String): String {
            val cleaned = title
                .map { c -> if (c.isLetterOrDigit() || c in " .-_()&'!,+") c else ' ' }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimStart('.')
                .take(MAX_TITLE_LENGTH)
                .trim()
            return cleaned.ifEmpty { "Game" }
        }

        private fun crcTag(crc32: Long): String = "%08X".format(crc32 and 0xFFFFFFFFL)

        private fun hasCrcTag(name: String, tag: String): Boolean {
            val suffix = "$tag]"
            if (!name.endsWith(suffix) || name.length <= suffix.length) return false
            return name[name.length - suffix.length - 1] in "[-"
        }
    }
}
