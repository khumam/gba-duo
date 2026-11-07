package com.khumam.gbaduo.emu.shaderlib

import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.library.artwork.Http
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Every `.slangp` preset in the shader repo at one commit. */
data class ShaderListing(val sha: String, val presets: List<String>, val fetchedAtMillis: Long)

object ShaderCatalog {
    /**
     * The shader library: GBA Duo's own copy of libretro's slang-shaders, with a few extra preset sets added (sonkun,
     * crt-guest-advanced). Laid out like the libretro repo, so the relative paths inside presets work the same.
     */
    const val REPO = "khumam/gba-duo-slang-shaders"

    /** The newest commit's sha, as plain text (what `raw.githubusercontent.com` pins files by). */
    const val COMMIT_URL = "https://api.github.com/repos/$REPO/commits/master"

    /** The whole file tree at [sha]: one request (about 6,700 entries, under the API's size limit today). */
    fun treeUrl(sha: String) = "https://api.github.com/repos/$REPO/git/trees/$sha?recursive=1"

    /** Where a file of the repo can be downloaded from at [sha]. */
    fun rawUrl(sha: String, path: String) = "https://raw.githubusercontent.com/$REPO/$sha/$path"

    /** The presets in a git tree answer; throws [IllegalArgumentException] when it is malformed or was cut short. */
    fun parse(json: String, sha: String, now: Long): ShaderListing {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed shader list: ${e.message}")
        }
        require(!root.optBoolean("truncated", false)) { "The shader list was cut short" }
        val tree = root.optJSONArray("tree") ?: throw IllegalArgumentException("No tree in the shader list")
        val presets = (0 until tree.length()).mapNotNull { tree.optJSONObject(it) }
            .filter { it.optString("type") == "blob" && it.optString("path").endsWith(".slangp") }
            .map { it.getString("path") }
            .sorted()
        return ShaderListing(sha, presets, now)
    }
}

/**
 * The list of library shaders, kept in [cacheFile] so it is browsable offline: used as is for 24 hours, refetched after
 * that or on request, and kept (even stale) when the refetch fails. Blocking; call off the main thread.
 */
class ShaderCatalogStore(
    private val http: Http,
    private val cacheFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun load(refresh: Boolean): Fetch<ShaderListing> {
        val cached = readCache()
        if (cached != null && !refresh && clock() - cached.fetchedAtMillis < MAX_AGE_MILLIS) return Fetch.Ok(cached)
        val fetched = fetch()
        if (fetched is Fetch.Ok) {
            writeCache(fetched.value)
            return fetched
        }
        return if (cached != null) Fetch.Ok(cached) else fetched
    }

    private fun fetch(): Fetch<ShaderListing> {
        try {
            val commit = http.get(COMMIT_HEADERS_URL, SHA_HEADERS)
            if (commit.code != 200) return Fetch.Failed(Fetch.Reason.DOWNLOAD_FAILED, "HTTP ${commit.code}")
            val sha = commit.body.toString(Charsets.UTF_8).trim()
            if (!SHA.matches(sha)) return Fetch.Failed(Fetch.Reason.UNSUPPORTED_CATALOG, "Unexpected commit answer")
            val tree = http.get(ShaderCatalog.treeUrl(sha), emptyMap())
            if (tree.code != 200) return Fetch.Failed(Fetch.Reason.DOWNLOAD_FAILED, "HTTP ${tree.code}")
            return try {
                Fetch.Ok(ShaderCatalog.parse(tree.body.toString(Charsets.UTF_8), sha, clock()))
            } catch (e: IllegalArgumentException) {
                Fetch.Failed(Fetch.Reason.UNSUPPORTED_CATALOG, e.message)
            }
        } catch (e: IOException) {
            return Fetch.Failed(Fetch.Reason.OFFLINE, e.message)
        }
    }

    // Every folder page of the browser loads the list again: the parsed list is kept while the file stays the same.
    private fun readCache(): ShaderListing? {
        val key = Triple(cacheFile.absolutePath, cacheFile.lastModified(), cacheFile.length())
        memo?.let { (k, listing) -> if (k == key) return listing }
        val listing = parseCache() ?: return null
        memo = key to listing
        return listing
    }

    private fun parseCache(): ShaderListing? = runCatching {
        val o = JSONObject(cacheFile.readText())
        // a list kept from another repo (the app used libretro's own before) names commits this one doesn't have
        if (o.optString("repo") != ShaderCatalog.REPO) return@runCatching null
        val presets = o.getJSONArray("presets")
        ShaderListing(o.getString("sha"), (0 until presets.length()).map { presets.getString(it) }, o.getLong("fetchedAt"))
    }.getOrNull()

    private fun writeCache(listing: ShaderListing) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val o = JSONObject()
                .put("repo", ShaderCatalog.REPO)
                .put("sha", listing.sha)
                .put("fetchedAt", listing.fetchedAtMillis)
                .put("presets", org.json.JSONArray(listing.presets))
            cacheFile.writeText(o.toString())
            memo = Triple(cacheFile.absolutePath, cacheFile.lastModified(), cacheFile.length()) to listing
        }
    }

    private companion object {
        @Volatile var memo: Pair<Triple<String, Long, Long>, ShaderListing>? = null
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val COMMIT_HEADERS_URL = ShaderCatalog.COMMIT_URL
        val SHA_HEADERS = mapOf("Accept" to "application/vnd.github.sha")
        val SHA = Regex("[0-9a-f]{7,64}")
    }
}
