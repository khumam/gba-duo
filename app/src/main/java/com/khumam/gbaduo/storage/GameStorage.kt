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

    private val consentFile: File get() = File(dir, "consent.json")

    /**
     * The save state made automatically just before a companion pack first changes this
     * game's memory, so the player can always go back. Not a numbered slot (and not
     * copied to the save folder).
     */
    val beforePackWriteState: File get() = File(statesDir, "before-pack-write.ss")

    /**
     * The player's answer to "let pack [packId] change this game?": true, false, or null
     * when not asked yet. Kept in this game's folder, per pack, with the pack's
     * [fingerprint] (PackFingerprint) when asked: if the pack has changed since (an
     * update, or another pack using the same id), it's null again, so the player is asked
     * about the code that will actually run.
     */
    fun writeConsent(packId: String, fingerprint: String): Boolean? {
        val json = runCatching { JSONObject(consentFile.readText()) }.getOrNull() ?: return null
        val packs = json.optJSONObject(MEMORY_WRITE) ?: return null
        val answer = packs.optJSONObject(packId) ?: return null
        if (answer.optString(FINGERPRINT) != fingerprint || !answer.has(ALLOWED)) return null
        return answer.optBoolean(ALLOWED)
    }

    fun setWriteConsent(packId: String, allowed: Boolean, fingerprint: String) {
        ensureDirs()
        val json = runCatching { JSONObject(consentFile.readText()) }.getOrNull() ?: JSONObject()
        val packs = json.optJSONObject(MEMORY_WRITE) ?: JSONObject().also { json.put(MEMORY_WRITE, it) }
        packs.put(packId, JSONObject().put(ALLOWED, allowed).put(FINGERPRINT, fingerprint))
        val tmp = File(dir, "consent.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(consentFile)) {
            consentFile.delete()
            tmp.renameTo(consentFile)
        }
    }

    /** `gba`, `gbc` or `gb`: the folder above this one. */
    val platformId: String get() = dir.parentFile?.name.orEmpty()

    /** `<Title> [<code>-]<CRC32>`: also the folder's name in the save folder, if one is set. */
    val folderName: String get() = dir.name

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
        private const val MEMORY_WRITE = "memory-write"
        private const val ALLOWED = "allowed"
        private const val FINGERPRINT = "fingerprint"

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

        /** The CRC32 tag of a game folder name (`"...[BPEE-1F1C08FB]"` -> `"1F1C08FB"`), or null if it isn't one. */
        fun crcTagOf(folderName: String): String? {
            val m = Regex("[\\[-]([0-9A-F]{8})]$").find(folderName) ?: return null
            return m.groupValues[1]
        }

        /** Whether two game folder names are the same game: same CRC, whatever the title part says. */
        fun sameGame(a: String, b: String): Boolean {
            val tag = crcTagOf(a) ?: return false
            return hasCrcTag(b, tag)
        }

        private fun hasCrcTag(name: String, tag: String): Boolean {
            val suffix = "$tag]"
            if (!name.endsWith(suffix) || name.length <= suffix.length) return false
            return name[name.length - suffix.length - 1] in "[-"
        }
    }
}
