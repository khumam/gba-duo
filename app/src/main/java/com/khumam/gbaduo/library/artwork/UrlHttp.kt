package com.khumam.gbaduo.library.artwork

import com.khumam.gbaduo.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** [Http] over HttpURLConnection. Reads at most one byte past the download cap, so huge files are cheap to refuse. */
class UrlHttp : Http {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "GBADuo/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code != 200) return HttpResponse(code, ByteArray(0))
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (out.size() <= SteamGridDb.MAX_DOWNLOAD) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
            return HttpResponse(code, out.toByteArray())
        } catch (e: IOException) {
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
