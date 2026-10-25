package com.khumam.gbaduo.library.artwork

import com.khumam.gbaduo.library.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ArtworkScraperTest {

    private val store = ArtworkStore(Files.createTempDirectory("scrape").toFile())

    private fun target(name: String, crc: Long? = name.hashCode().toLong() and 0xFFFF) =
        ScrapeTarget(Platform.GBA, "$name.gba", name, "", crc)

    private fun found(tag: String, source: String) = Found(tag.toByteArray(), source, tag)

    private fun scraper(
        folder: ((ScrapeTarget, Long) -> Found?)? = null,
        web: ((ScrapeTarget) -> Sgdb<Found>)? = null,
        lock: (Platform, Long) -> String? = { _, _ -> null },
        encode: (ByteArray) -> ByteArray? = { it },
    ) = ArtworkScraper(store, folder, web, lock, encode, now = { 1L })

    @Test
    fun folderIsTriedBeforeSteamGridDb() {
        var webCalls = 0
        val s = scraper(folder = { _, _ -> found("f", "folder") }, web = { webCalls++; Sgdb.Failed(Sgdb.Reason.NOT_FOUND) })
        val t = target("A")
        assertEquals(Outcome.FOUND, s.scrapeOne(t, replace = false))
        assertEquals("folder", store.meta("gba", t.crc!!)!!.source)
        assertEquals(0, webCalls)
    }

    @Test
    fun steamGridDbIsUsedWhenFolderMisses() {
        val s = scraper(folder = { _, _ -> null }, web = { Sgdb.Ok(found("w", "steamgriddb")) })
        val t = target("A")
        assertEquals(Outcome.FOUND, s.scrapeOne(t, false))
        assertEquals("steamgriddb", store.meta("gba", t.crc!!)!!.source)
    }

    @Test
    fun noSourceSetUpMeansNotFound() {
        assertEquals(Outcome.NOT_FOUND, scraper().scrapeOne(target("A"), false))
    }

    @Test
    fun existingArtIsKeptUnlessReplacing() {
        val t = target("A")
        store.put("gba", t.crc!!, byteArrayOf(1), "folder", "old", 0L)
        val s = scraper(folder = { _, _ -> found("new", "folder") })
        assertEquals(Outcome.SKIPPED, s.scrapeOne(t, false))
        assertEquals("old", store.meta("gba", t.crc!!)!!.ref)
        assertEquals(Outcome.FOUND, s.scrapeOne(t, true))
        assertEquals("new", store.meta("gba", t.crc!!)!!.ref)
    }

    @Test
    fun lockedGamesAreNeverTouched() {
        val t = target("A")
        for (lock in listOf("none", "picked")) {
            val s = scraper(folder = { _, _ -> found("new", "folder") }, lock = { _, _ -> lock })
            assertEquals(Outcome.SKIPPED, s.scrapeOne(t, replace = true))
        }
        assertNull(store.meta("gba", t.crc!!))
    }

    @Test
    fun unreadableRomFails() {
        assertEquals(Outcome.FAILED, scraper().scrapeOne(target("A", crc = null), false))
    }

    @Test
    fun rejectedImageFallsThroughToNextSource() {
        val s = scraper(
            folder = { _, _ -> found("bad", "folder") },
            web = { Sgdb.Ok(found("good", "steamgriddb")) },
            encode = { if (String(it) == "bad") null else it },
        )
        val t = target("A")
        assertEquals(Outcome.FOUND, s.scrapeOne(t, false))
        assertEquals("steamgriddb", store.meta("gba", t.crc!!)!!.source)
    }

    @Test
    fun networkFailureFailsTheGame() {
        val s = scraper(web = { Sgdb.Failed(Sgdb.Reason.OFFLINE) })
        assertEquals(Outcome.FAILED, s.scrapeOne(target("A"), false))
    }

    @Test
    fun runCountsAndReportsProgress() {
        val s = scraper(folder = { t, _ -> if (t.title == "B") null else found(t.title, "folder") })
        val progress = mutableListOf<Pair<Int, Int>>()
        val summary = s.run(listOf(target("A"), target("B"), target("C")), false, { false }) { done, total -> progress += done to total }
        assertEquals(Summary(found = 2, notFound = 1, skipped = 0, failed = 0, stopped = Stop.NONE), summary)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun runStopsWhenCancelled() {
        var seen = 0
        val s = scraper(folder = { t, _ -> found(t.title, "folder") })
        val summary = s.run(listOf(target("A"), target("B"), target("C")), false, { seen >= 1 }) { done, _ -> seen = done }
        assertEquals(1, summary.found)
        assertEquals(Stop.CANCELLED, summary.stopped)
    }

    @Test
    fun runStopsOnSlowDownAndOnRejectedKey() {
        for ((reason, stop) in listOf(Sgdb.Reason.SLOW_DOWN to Stop.SLOW_DOWN, Sgdb.Reason.KEY_REJECTED to Stop.KEY_REJECTED)) {
            val s = scraper(web = { Sgdb.Failed(reason) })
            val summary = s.run(listOf(target("A"), target("B")), false, { false }) { _, _ -> }
            assertEquals(stop, summary.stopped)
            assertEquals(0, summary.found)
        }
    }
}
