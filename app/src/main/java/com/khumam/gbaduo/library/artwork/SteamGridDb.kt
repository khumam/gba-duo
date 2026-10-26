package com.khumam.gbaduo.library.artwork

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

class HttpResponse(val code: Int, val body: ByteArray)

/** One blocking GET. Throws [IOException] when the server can't be reached. */
interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** A SteamGridDB answer: a value, or why there isn't one. */
sealed interface Sgdb<out T> {
    data class Ok<T>(val value: T) : Sgdb<T>
    data class Failed(val reason: Reason) : Sgdb<Nothing>
    enum class Reason { NOT_FOUND, KEY_REJECTED, SLOW_DOWN, OFFLINE }
}

data class SgdbGame(val id: Long, val name: String, val year: Int?)
data class SgdbGrid(val id: Long, val url: String, val thumb: String, val width: Int, val height: Int)

/**
 * SteamGridDB API v2 with the player's own key. Pure over [Http]; [sleep] waits out a
 * rate limit. Game titles are the only thing sent.
 */
class SteamGridDb(
    private val key: String,
    private val http: Http,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    fun search(title: String): Sgdb<List<SgdbGame>> =
        getJson("$BASE/search/autocomplete/${encode(title)}") { json ->
            val data = json.optJSONArray("data")
            (0 until (data?.length() ?: 0)).mapNotNull { i ->
                val game = data!!.optJSONObject(i) ?: return@mapNotNull null
                val release = game.optLong("release_date", 0).takeIf { it > 0 }
                SgdbGame(game.getLong("id"), game.optString("name"), release?.let(::yearOf))
            }
        }

    /** The cover grids for a game, best-scored first, in portrait sizes: the cartridge label is portrait. */
    fun grids(gameId: Long): Sgdb<List<SgdbGrid>> {
        val base = "$BASE/grids/game/$gameId?types=static&nsfw=false&humor=false&epilepsy=false&mimes=image/png,image/jpeg"
        val sized = "&dimensions=600x900,342x482,660x930"
        val first = getJson(base + sized, ::parseGrids)
        if (first is Sgdb.Ok && first.value.isEmpty()) return getJson(base, ::parseGrids)
        return first
    }

    /** Title -> first game found -> its best cover. Retries a subtitled title with just the main title. */
    fun cover(title: String): Sgdb<SgdbGrid> {
        val game = findGame(title)
        if (game !is Sgdb.Ok) return game as Sgdb.Failed
        return when (val grids = grids(game.value.id)) {
            is Sgdb.Failed -> grids
            is Sgdb.Ok -> grids.value.firstOrNull()?.let { Sgdb.Ok(it) } ?: Sgdb.Failed(Sgdb.Reason.NOT_FOUND)
        }
    }

    private fun findGame(title: String): Sgdb<SgdbGame> {
        val tries = listOf(title, title.substringBefore(":").substringBefore(" - ").trim()).filter { it.isNotBlank() }.distinct()
        for (t in tries) {
            when (val result = search(t)) {
                is Sgdb.Failed -> if (result.reason != Sgdb.Reason.NOT_FOUND) return result
                is Sgdb.Ok -> result.value.firstOrNull()?.let { return Sgdb.Ok(it) }
            }
        }
        return Sgdb.Failed(Sgdb.Reason.NOT_FOUND)
    }

    /**
     * The image bytes, from the image server (CDN). It is a different host from the API and
     * answers 401 to a request that carries the API key, so none is sent. Anything over
     * [MAX_DOWNLOAD] counts as not found.
     */
    fun download(url: String): Sgdb<ByteArray> = when (val r = request(url, authorized = false)) {
        is Sgdb.Failed -> r
        is Sgdb.Ok -> if (r.value.body.size > MAX_DOWNLOAD) Sgdb.Failed(Sgdb.Reason.NOT_FOUND) else Sgdb.Ok(r.value.body)
    }

    /** Checks the key with a harmless search. */
    fun testKey(): Sgdb<Unit> = when (val r = search("zelda")) {
        is Sgdb.Ok -> Sgdb.Ok(Unit)
        is Sgdb.Failed -> r
    }

    private fun parseGrids(json: JSONObject): List<SgdbGrid> {
        val data = json.optJSONArray("data")
        return (0 until (data?.length() ?: 0)).mapNotNull { i ->
            val g = data!!.optJSONObject(i) ?: return@mapNotNull null
            val url = g.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SgdbGrid(g.getLong("id"), url, g.optString("thumb").ifBlank { url }, g.optInt("width"), g.optInt("height"))
        }
    }

    private fun <T> getJson(url: String, parse: (JSONObject) -> T): Sgdb<T> = when (val r = request(url)) {
        is Sgdb.Failed -> r
        is Sgdb.Ok -> runCatching { Sgdb.Ok(parse(JSONObject(String(r.value.body)))) }
            .getOrElse { Sgdb.Failed(Sgdb.Reason.OFFLINE) }
    }

    private fun request(url: String, authorized: Boolean = true): Sgdb<HttpResponse> {
        var waited = false
        while (true) {
            val response = try {
                http.get(url, if (authorized) mapOf("Authorization" to "Bearer $key") else emptyMap())
            } catch (e: IOException) {
                return Sgdb.Failed(Sgdb.Reason.OFFLINE)
            }
            when (response.code) {
                200 -> return Sgdb.Ok(response)
                // Only the API can say the key is bad; the image server just refuses.
                401, 403 -> return Sgdb.Failed(if (authorized) Sgdb.Reason.KEY_REJECTED else Sgdb.Reason.OFFLINE)
                404 -> return Sgdb.Failed(Sgdb.Reason.NOT_FOUND)
                429 -> {
                    if (waited) return Sgdb.Failed(Sgdb.Reason.SLOW_DOWN)
                    waited = true
                    sleep(RATE_LIMIT_WAIT_MS)
                }
                else -> return Sgdb.Failed(Sgdb.Reason.OFFLINE)
            }
        }
    }

    private fun yearOf(epochSeconds: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochSeconds * 1000 }.get(Calendar.YEAR)

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val BASE = "https://www.steamgriddb.com/api/v2"
        const val MAX_DOWNLOAD = 8 * 1024 * 1024
        private const val RATE_LIMIT_WAIT_MS = 5000L
    }
}
