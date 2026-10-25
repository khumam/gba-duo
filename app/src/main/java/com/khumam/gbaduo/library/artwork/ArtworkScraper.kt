package com.khumam.gbaduo.library.artwork

import com.khumam.gbaduo.library.Platform

/** A game to find art for. [crc] is read lazily (hashing a ROM is slow) and is null for an unreadable ROM. */
class ScrapeTarget(
    val platform: Platform,
    val fileName: String,
    val title: String,
    val gameCode: String,
    crcSource: () -> Long?,
) {
    constructor(platform: Platform, fileName: String, title: String, gameCode: String, crc: Long?) :
        this(platform, fileName, title, gameCode, { crc })

    val crc: Long? by lazy(crcSource)
}

/** An image a source found. [source] is `folder` or `steamgriddb`; [ref] says where from. */
class Found(val bytes: ByteArray, val source: String, val ref: String)

enum class Outcome { FOUND, NOT_FOUND, SKIPPED, FAILED }

enum class Stop { NONE, CANCELLED, SLOW_DOWN, KEY_REJECTED }

data class Summary(val found: Int, val notFound: Int, val skipped: Int, val failed: Int, val stopped: Stop)

/**
 * Finds art for games: the artwork folder first, then SteamGridDB, whichever are set up
 * (a null source is off). A game whose lock is `none` (art removed) or `picked` (chosen by
 * the player) is never touched. [encode] turns downloaded bytes into what the cache keeps;
 * null rejects the image and the next source is tried. Pure; the Android bits are injected.
 */
class ArtworkScraper(
    private val store: ArtworkStore,
    private val folder: ((ScrapeTarget, Long) -> Found?)?,
    private val web: ((ScrapeTarget) -> Sgdb<Found>)?,
    private val lock: (Platform, Long) -> String?,
    private val encode: (ByteArray) -> ByteArray?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var stopRequested = Stop.NONE

    fun scrapeOne(target: ScrapeTarget, replace: Boolean): Outcome {
        val crc = target.crc ?: return Outcome.FAILED
        val platform = target.platform
        if (lock(platform, crc) != null) return Outcome.SKIPPED
        if (!replace && store.file(platform.id, crc) != null) return Outcome.SKIPPED

        folder?.invoke(target, crc)?.let { if (keep(target, crc, it)) return Outcome.FOUND }

        var failed = false
        if (web != null) {
            when (val result = web.invoke(target)) {
                is Sgdb.Ok -> if (keep(target, crc, result.value)) return Outcome.FOUND
                is Sgdb.Failed -> when (result.reason) {
                    Sgdb.Reason.NOT_FOUND -> Unit
                    Sgdb.Reason.OFFLINE -> failed = true
                    Sgdb.Reason.SLOW_DOWN -> { stopRequested = Stop.SLOW_DOWN; failed = true }
                    Sgdb.Reason.KEY_REJECTED -> { stopRequested = Stop.KEY_REJECTED; failed = true }
                }
            }
        }
        return if (failed) Outcome.FAILED else Outcome.NOT_FOUND
    }

    private fun keep(target: ScrapeTarget, crc: Long, found: Found): Boolean {
        val bytes = encode(found.bytes) ?: return false
        store.put(target.platform.id, crc, bytes, found.source, found.ref, now())
        return true
    }

    /** Runs through [targets]; [onProgress] gets (done, total) before the first game and after each. */
    fun run(targets: List<ScrapeTarget>, replace: Boolean, cancelled: () -> Boolean, onProgress: (Int, Int) -> Unit): Summary {
        stopRequested = Stop.NONE
        var found = 0; var notFound = 0; var skipped = 0; var failed = 0
        onProgress(0, targets.size)
        for ((i, target) in targets.withIndex()) {
            if (cancelled()) return Summary(found, notFound, skipped, failed, Stop.CANCELLED)
            when (scrapeOne(target, replace)) {
                Outcome.FOUND -> found++
                Outcome.NOT_FOUND -> notFound++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
            onProgress(i + 1, targets.size)
            if (stopRequested != Stop.NONE) return Summary(found, notFound, skipped, failed, stopRequested)
        }
        return Summary(found, notFound, skipped, failed, Stop.NONE)
    }
}
