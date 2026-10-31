package com.khumam.gbaduo.companion

import org.json.JSONObject

data class CatalogGame(val title: String, val gameCode: String)

data class CatalogPack(
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val author: String,
    val official: Boolean,
    val download: String,
    val sha256: String?,
    val games: List<CatalogGame>,
    val minAppVersion: String?,
    val canModifyGame: Boolean,
)

enum class CatalogStatus { NEW, INSTALLED, UPDATE, NEEDS_NEWER_APP }

/** The web catalog of companion packs (`companions.json`): parsing and the questions the page asks of it. */
object PackCatalog {
    const val URL = "https://raw.githubusercontent.com/khumam/gba-duo-companions/refs/heads/main/companions.json"
    const val SUPPORTED_FORMAT_VERSION = 1

    class UnsupportedCatalogException(message: String) : Exception(message)

    /** Entries missing something the app needs are skipped, so one bad entry doesn't hide the rest. */
    fun parse(json: String): List<CatalogPack> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UnsupportedCatalogException("Malformed catalog: ${e.message}")
        }
        val format = root.optInt("formatVersion", -1)
        if (format != SUPPORTED_FORMAT_VERSION) throw UnsupportedCatalogException("Unsupported catalog formatVersion $format")
        val entries = root.optJSONArray("companions") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            runCatching { entries.optJSONObject(i)?.let(::parsePack) }.getOrNull()
        }
    }

    private fun parsePack(o: JSONObject): CatalogPack? {
        val id = o.optString("id").takeIf { PackManifest.isValidId(it) } ?: return null
        val title = o.optString("title").ifBlank { return null }
        val version = o.optString("version").ifBlank { return null }
        val download = o.optString("download").takeIf { it.startsWith("https://") } ?: return null
        val gamesJson = o.optJSONArray("games") ?: return null
        val games = (0 until gamesJson.length()).mapNotNull { i ->
            val g = gamesJson.optJSONObject(i) ?: return@mapNotNull null
            val code = g.optString("gameCode").ifBlank { return@mapNotNull null }
            CatalogGame(g.optString("title", code), code)
        }
        if (games.isEmpty()) return null
        return CatalogPack(
            id = id,
            title = title,
            description = o.optString("description"),
            version = version,
            author = o.optString("author"),
            official = o.optBoolean("official", false),
            download = download,
            sha256 = o.optString("sha256").lowercase().ifBlank { null },
            games = games,
            minAppVersion = o.optString("minAppVersion").ifBlank { null },
            canModifyGame = o.optBoolean("canModifyGame", false),
        )
    }

    private fun normalize(code: String) = code.trim().uppercase()

    fun forGame(packs: List<CatalogPack>, gameCode: String): List<CatalogPack> {
        val wanted = normalize(gameCode)
        if (wanted.isEmpty()) return emptyList()
        return packs.filter { pack -> pack.games.any { normalize(it.gameCode) == wanted } }
    }

    /**
     * Packs for another region or revision of the same game: GBA codes share their first
     * three letters. GB/GBC codes (`DMG-`/`CGB-`) carry no game identity, so they never relate.
     */
    fun relatedTo(packs: List<CatalogPack>, gameCode: String): List<CatalogPack> {
        val wanted = normalize(gameCode)
        if (wanted.length != 4 || wanted.contains('-')) return emptyList()
        val exact = forGame(packs, wanted).map { it.id }.toSet()
        return packs.filter { pack ->
            pack.id !in exact && pack.games.any {
                val code = normalize(it.gameCode)
                code.length == 4 && !code.contains('-') && code.take(3) == wanted.take(3)
            }
        }
    }

    /** Compares dot-separated numbers; a missing or non-numeric segment counts as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun status(pack: CatalogPack, installedVersion: String?, appVersion: String): CatalogStatus =
        status(pack.version, pack.minAppVersion, installedVersion, appVersion)

    fun status(version: String, minAppVersion: String?, installedVersion: String?, appVersion: String): CatalogStatus = when {
        minAppVersion != null && compareVersions(minAppVersion, appVersion) > 0 -> CatalogStatus.NEEDS_NEWER_APP
        installedVersion == null -> CatalogStatus.NEW
        compareVersions(version, installedVersion) > 0 -> CatalogStatus.UPDATE
        else -> CatalogStatus.INSTALLED
    }
}
