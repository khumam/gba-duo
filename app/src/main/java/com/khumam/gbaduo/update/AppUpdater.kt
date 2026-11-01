package com.khumam.gbaduo.update

import com.khumam.gbaduo.BuildConfig
import com.khumam.gbaduo.library.artwork.Http
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What a check for a newer GBADuo found. */
sealed interface UpdateCheck {
    data class Available(val release: AppRelease) : UpdateCheck
    object UpToDate : UpdateCheck
    data class Failed(val detail: String?) : UpdateCheck
}

/** Looks for a newer release on GitHub and downloads its APK. Blocking; call off the main thread. */
class AppUpdater(private val http: Http, private val dir: File) {

    fun check(installed: String = BuildConfig.VERSION_NAME, url: String = AppRelease.LATEST_URL): UpdateCheck {
        val response = try {
            http.get(url, mapOf("Accept" to "application/vnd.github+json"))
        } catch (e: IOException) {
            return UpdateCheck.Failed(e.message)
        }
        if (response.code != 200) return UpdateCheck.Failed("HTTP ${response.code}")
        val release = AppRelease.parse(response.body.toString(Charsets.UTF_8)) ?: return UpdateCheck.UpToDate
        return if (release.isNewerThan(installed)) UpdateCheck.Available(release) else UpdateCheck.UpToDate
    }

    /** Saves [release]'s APK and returns it; [progress] gets bytes so far and the total (-1 if unknown). Throws [IOException]. */
    fun download(release: AppRelease, progress: (Long, Long) -> Unit): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "gbaduo-${release.version}.apk")
        val partial = File(dir, target.name + ".part")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "GBADuo/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Incomplete download")
            if (!partial.renameTo(target)) throw IOException("Could not save the APK")
            return target
        } catch (e: IOException) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
