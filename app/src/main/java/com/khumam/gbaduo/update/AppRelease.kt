package com.khumam.gbaduo.update

import com.khumam.gbaduo.companion.PackCatalog
import org.json.JSONException
import org.json.JSONObject

/** A published GBADuo build: its version, release notes and where its APK lives. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long) {

    fun isNewerThan(installed: String) = PackCatalog.compareVersions(version, installed) > 0

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/khumam/gba-duo/releases/latest"

        /** The release behind GitHub's "latest release" JSON, or null when it's a draft, a pre-release or has no APK. */
        fun parse(json: String): AppRelease? = try {
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) null else {
                val assets = root.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("browser_download_url").startsWith("https://") }
                val version = root.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                if (apk == null || version.isEmpty()) null else AppRelease(
                    version = version,
                    notes = root.optString("body").trim(),
                    apkUrl = apk.getString("browser_download_url"),
                    apkName = apk.getString("name"),
                    size = apk.optLong("size", -1L),
                )
            }
        } catch (e: JSONException) {
            null
        }
    }
}
