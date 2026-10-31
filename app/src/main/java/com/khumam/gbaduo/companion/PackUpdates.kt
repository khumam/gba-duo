package com.khumam.gbaduo.companion

import android.content.SharedPreferences

/**
 * What the library knows about newer pack versions without opening the catalog page: a
 * snapshot of the catalog's versions, refreshed at most once per [INTERVAL_MS], compared
 * against the installed packs each time so installing an update clears its badge at once.
 */
class PackUpdates(private val prefs: SharedPreferences) {

    fun isStale(now: Long): Boolean {
        if (!prefs.contains(KEY_CHECKED_AT)) return true
        return now - prefs.getLong(KEY_CHECKED_AT, 0L) !in 0 until INTERVAL_MS
    }

    fun save(packs: List<CatalogPack>, now: Long) {
        val lines = packs.map { listOf(it.id, it.version, it.minAppVersion.orEmpty()).joinToString("\t") }.toSet()
        prefs.edit().putStringSet(KEY_SNAPSHOT, lines).putLong(KEY_CHECKED_AT, now).apply()
    }

    /** A failed check retries after [RETRY_MS] rather than on every library visit. */
    fun markFailed(now: Long) {
        prefs.edit().putLong(KEY_CHECKED_AT, now - INTERVAL_MS + RETRY_MS).apply()
    }

    /** Ids of installed packs ([installed]: id to version) that the snapshot has a usable newer version of. */
    fun updatableIds(installed: Map<String, String>, appVersion: String): Set<String> {
        val snapshot = prefs.getStringSet(KEY_SNAPSHOT, null) ?: return emptySet()
        return snapshot.mapNotNull { line ->
            val (id, version, minApp) = line.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
            val status = PackCatalog.status(version, minApp.ifBlank { null }, installed[id], appVersion)
            id.takeIf { status == CatalogStatus.UPDATE }
        }.toSet()
    }

    companion object {
        const val INTERVAL_MS = 12L * 60 * 60 * 1000
        const val RETRY_MS = 30L * 60 * 1000
        private const val KEY_SNAPSHOT = "pack_catalog_snapshot"
        private const val KEY_CHECKED_AT = "pack_catalog_checked_at"
    }
}
