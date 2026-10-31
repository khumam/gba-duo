package com.khumam.gbaduo.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackUpdatesTest {
    private val updates = PackUpdates(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("pack_updates_test", Context.MODE_PRIVATE),
    )

    private fun pack(id: String, version: String, min: String? = null) = CatalogPack(
        id, id, "", version, "", false, "https://x/$id.zip", null,
        listOf(CatalogGame("G", "AAAA")), min, false,
    )

    @Test
    fun staleUntilCheckedThenFreshForTwelveHours() {
        assertTrue(updates.isStale(1_000L))
        updates.save(emptyList(), now = 1_000L)
        assertFalse(updates.isStale(1_000L + PackUpdates.INTERVAL_MS - 1))
        assertTrue(updates.isStale(1_000L + PackUpdates.INTERVAL_MS))
    }

    @Test
    fun failedCheckRetriesSoon() {
        updates.markFailed(now = 5_000_000L)
        assertFalse(updates.isStale(5_000_000L + PackUpdates.RETRY_MS - 1))
        assertTrue(updates.isStale(5_000_000L + PackUpdates.RETRY_MS))
    }

    @Test
    fun onlyInstalledPacksWithUsableNewerVersionCount() {
        updates.save(
            listOf(pack("a", "1.1.0"), pack("b", "1.0.0"), pack("c", "2.0.0", min = "9.9.9"), pack("d", "3.0.0")),
            now = 1L,
        )
        val installed = mapOf("a" to "1.0.0", "b" to "1.0.0", "c" to "1.0.0")
        assertEquals(setOf("a"), updates.updatableIds(installed, appVersion = "0.1.8"))
        // Installing the update clears it without another check.
        assertTrue(updates.updatableIds(mapOf("a" to "1.1.0"), "0.1.8").isEmpty())
    }

    @Test
    fun noSnapshotMeansNoUpdates() {
        assertTrue(updates.updatableIds(mapOf("a" to "1.0.0"), "0.1.8").isEmpty())
    }
}
