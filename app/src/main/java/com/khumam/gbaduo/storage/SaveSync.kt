package com.khumam.gbaduo.storage

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps one game's folder in private storage in step with its copy in the save folder
 * the player picked: `game.sav`, `states/slotN.ss` and `pack.json`. Pure, no Android.
 *
 * The game always runs on the private copy (mGBA needs a file path). This copies
 * whichever side changed since the last sync to the other side:
 * - Only one side changed: it's copied over.
 * - Both changed: the newer one wins, and the other is kept next to it in the save
 *   folder as `<name>.conflict-<time>`, never overwritten silently.
 * - A file missing on one side is copied back, never deleted on the other: an emptied
 *   SD card can't wipe the saves. Only `pack.json` deletions travel (clearing a pack).
 *
 * "Since the last sync" comes from a small record in the private game folder
 * ([BASE_FILE]): each file's hash and how both copies looked right after it was synced.
 * Copies are written to `<name>.partial` and only then swapped in, so an interrupted copy
 * leaves the old file in place.
 *
 * @param rootId identifies the save folder; a record made for another folder is ignored.
 * @param clock now, in epoch millis (conflict file names).
 */
class SaveSync(
    private val local: FileSyncDir,
    private val remote: SyncDir,
    private val rootId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Direction {
        /** Before the game starts: copy either way. */
        BOTH,

        /** While the game runs: only copy out; the private copy is in use and must not change. */
        OUT,
    }

    /** What one sync did, file by relative path. */
    data class Result(
        val copiedOut: List<String> = emptyList(),
        val copiedIn: List<String> = emptyList(),
        val conflicts: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    ) {
        val ok get() = failed.isEmpty()
    }

    private data class Base(val hash: String, val localSize: Long, val localModified: Long, val remoteSize: Long, val remoteModified: Long)

    fun sync(direction: Direction): Result {
        recoverInterrupted(remote)
        remote.subdir(STATES, create = false)?.let(::recoverInterrupted)

        val base = loadBase()
        val out = mutableListOf<String>()
        val inn = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (path in trackedPaths()) {
            try {
                when (syncFile(path, base, direction)) {
                    Action.OUT -> out += path
                    Action.IN -> inn += path
                    Action.CONFLICT -> conflicts += path
                    Action.NONE -> Unit
                }
            } catch (e: IOException) {
                failed += path
            }
        }
        saveBase(base)
        return Result(out, inn, conflicts, failed)
    }

    private enum class Action { NONE, OUT, IN, CONFLICT }

    private fun syncFile(path: String, base: MutableMap<String, Base>, direction: Direction): Action {
        val (dirName, name) = split(path)
        val localDir = if (dirName == null) local else local.subdir(dirName, create = false)
        val remoteDir = if (dirName == null) remote else remote.subdir(dirName, create = false)
        val l = localDir?.entry(name)?.takeIf { !it.isDir }
        val r = remoteDir?.entry(name)?.takeIf { !it.isDir }
        val b = base[path]

        if (l == null && r == null) {
            base.remove(path)
            return Action.NONE
        }

        val localHash = l?.let { hash(localDir!!, name) }
        val localChanged = l != null && (b == null || localHash != b.hash)
        val remoteChanged = r != null && (b == null || r.size != b.remoteSize || r.modified != b.remoteModified)

        // pack.json is the one file whose deletion travels: clearing a pack must stick.
        if (name == PACK && b != null) {
            if (l == null && !remoteChanged) {
                remoteDir!!.delete(name)
                base.remove(path)
                return Action.OUT
            }
            if (r == null && !localChanged && direction == Direction.BOTH) {
                localDir!!.delete(name)
                base.remove(path)
                return Action.IN
            }
        }

        return when {
            // Also when it's gone from the save folder while unchanged here: it's put back.
            l != null && (r == null || (localChanged && !remoteChanged)) -> {
                copyOut(dirName, name, localHash!!, base, path)
                Action.OUT
            }
            r != null && (l == null || (remoteChanged && !localChanged)) -> {
                if (direction == Direction.OUT) return Action.NONE // next start brings it in
                copyIn(dirName, name, remoteDir!!, base, path)
                Action.IN
            }
            !localChanged && !remoteChanged -> Action.NONE
            else -> resolveConflict(dirName, name, localDir!!, remoteDir!!, l!!, r!!, localHash!!, base, path, direction)
        }
    }

    /** Both copies changed since the last sync. */
    private fun resolveConflict(
        dirName: String?, name: String, localDir: FileSyncDir, remoteDir: SyncDir,
        l: SyncEntry, r: SyncEntry, localHash: String, base: MutableMap<String, Base>, path: String,
        direction: Direction,
    ): Action {
        val remoteHash = hash(remoteDir, name)
        if (remoteHash == localHash) {
            base[path] = Base(localHash, l.size, l.modified, r.size, r.modified)
            return Action.NONE
        }
        val conflictName = "$name.conflict-${stamp()}"
        // While playing, the private copy is in use: it wins whatever the times say.
        val localWins = direction == Direction.OUT || l.modified >= r.modified
        if (localWins) {
            // The save folder's version steps aside as the conflict copy.
            if (!remoteDir.rename(name, conflictName)) {
                copyBetween(remoteDir, name, remoteDir, conflictName)
            }
            copyOut(dirName, name, localHash, base, path)
        } else {
            // This device's version is kept in the save folder as the conflict copy.
            copyBetween(localDir, name, remoteDir, conflictName)
            copyIn(dirName, name, remoteDir, base, path)
        }
        return Action.CONFLICT
    }

    private fun copyOut(dirName: String?, name: String, hash: String, base: MutableMap<String, Base>, path: String) {
        val from = if (dirName == null) local else local.subdir(dirName, create = false) ?: throw IOException("no $dirName")
        val to = if (dirName == null) remote else remote.subdir(dirName, create = true) ?: throw IOException("can't create $dirName")
        copyBetween(from, name, to, name)
        val l = from.entry(name) ?: throw IOException("$path vanished")
        val r = to.entry(name) ?: throw IOException("$path not written")
        base[path] = Base(hash, l.size, l.modified, r.size, r.modified)
    }

    private fun copyIn(dirName: String?, name: String, remoteDir: SyncDir, base: MutableMap<String, Base>, path: String) {
        val to = if (dirName == null) local else local.subdir(dirName, create = true) ?: throw IOException("can't create $dirName")
        copyBetween(remoteDir, name, to, name)
        val l = to.entry(name) ?: throw IOException("$path not written")
        val r = remoteDir.entry(name) ?: throw IOException("$path vanished")
        base[path] = Base(hash(to, name), l.size, l.modified, r.size, r.modified)
    }

    /**
     * Copies [fromName] to [toName] through `<toName>.partial`: the old [toName] is
     * only replaced once the new bytes are all written (moved to `.old` meanwhile, and
     * restored if the swap fails), so a copy cut off half-way never leaves a broken save.
     */
    private fun copyBetween(from: SyncDir, fromName: String, to: SyncDir, toName: String) {
        val partial = "$toName$PARTIAL"
        val old = "$toName$OLD"
        to.delete(partial)
        try {
            from.openRead(fromName).use { input -> to.openWrite(partial).use { output -> input.copyTo(output) } }
        } catch (e: IOException) {
            to.delete(partial)
            throw e
        } catch (e: RuntimeException) {
            to.delete(partial)
            throw IOException(e)
        }
        val hadOld = to.entry(toName) != null
        if (hadOld) {
            to.delete(old)
            if (!to.rename(toName, old)) {
                // A provider that can't rename (some cloud folders): write the finished bytes
                // over the file itself. The complete copy stays in .partial until that worked.
                overwriteFrom(to, partial, toName)
                return
            }
        }
        if (!to.rename(partial, toName)) {
            if (hadOld) to.rename(old, toName)
            overwriteFrom(to, partial, toName)
            return
        }
        if (hadOld) to.delete(old)
    }

    private fun overwriteFrom(dir: SyncDir, partial: String, toName: String) {
        try {
            dir.openRead(partial).use { input -> dir.openWrite(toName).use { output -> input.copyTo(output) } }
        } catch (e: IOException) {
            throw IOException("can't replace $toName", e)
        }
        dir.delete(partial)
    }

    /** After a copy that was cut off between steps: put back a `.old` whose file is gone, drop stray `.partial`s. */
    private fun recoverInterrupted(dir: SyncDir) {
        val names = dir.entries().map { it.name }.toSet()
        for (n in names) {
            when {
                n.endsWith(OLD) -> {
                    val real = n.removeSuffix(OLD)
                    if (real !in names) dir.rename(n, real) else dir.delete(n)
                }
                n.endsWith(PARTIAL) -> dir.delete(n) // finished copies never stay as .partial
            }
        }
    }

    /** The files that belong to a game, on either side. */
    private fun trackedPaths(): Set<String> {
        val paths = sortedSetOf<String>()
        fun scan(dir: SyncDir?, prefix: String?) {
            dir ?: return
            for (e in dir.entries()) {
                if (e.isDir) continue
                val tracked = if (prefix == null) e.name == SAVE || e.name == PACK else STATE_NAME.matches(e.name)
                if (tracked) paths += if (prefix == null) e.name else "$prefix/${e.name}"
            }
        }
        scan(local, null)
        scan(remote, null)
        scan(local.subdir(STATES, create = false), STATES)
        scan(remote.subdir(STATES, create = false), STATES)
        // Files the record knows about but which are gone on both sides drop out by themselves.
        return paths
    }

    private fun split(path: String): Pair<String?, String> {
        val i = path.indexOf('/')
        return if (i < 0) null to path else path.substring(0, i) to path.substring(i + 1)
    }

    private fun hash(dir: SyncDir, name: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        dir.openRead(name).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(clock()))

    // ---- the record --------------------------------------------------------------

    private val baseFile get() = File(local.dir, BASE_FILE)

    private fun loadBase(): MutableMap<String, Base> {
        val out = mutableMapOf<String, Base>()
        val json = runCatching { JSONObject(baseFile.readText()) }.getOrNull() ?: return out
        if (json.optString("root") != rootId) return out
        val files = json.optJSONObject("files") ?: return out
        for (key in files.keys()) {
            val f = files.getJSONObject(key)
            out[key] = Base(f.getString("hash"), f.getLong("ls"), f.getLong("lm"), f.getLong("rs"), f.getLong("rm"))
        }
        return out
    }

    private fun saveBase(base: Map<String, Base>) {
        val files = JSONObject()
        for ((k, b) in base) {
            files.put(k, JSONObject().put("hash", b.hash).put("ls", b.localSize).put("lm", b.localModified)
                .put("rs", b.remoteSize).put("rm", b.remoteModified))
        }
        local.dir.mkdirs()
        val tmp = File(local.dir, "$BASE_FILE.tmp")
        tmp.writeText(JSONObject().put("root", rootId).put("files", files).toString())
        if (!tmp.renameTo(baseFile)) {
            baseFile.delete()
            tmp.renameTo(baseFile)
        }
    }

    companion object {
        const val SAVE = "game.sav"
        const val PACK = "pack.json"
        const val STATES = "states"
        const val BASE_FILE = ".sync.json"
        private const val PARTIAL = ".partial"
        private const val OLD = ".old"
        private val STATE_NAME = Regex("slot\\d+\\.ss")

        /** True when [a] and [b] hold the same tracked files with the same bytes. For checking a first-time move. */
        fun sameContents(a: SyncDir, b: SyncDir): Boolean {
            fun files(dir: SyncDir): Map<String, Long> {
                val out = mutableMapOf<String, Long>()
                for (e in dir.entries()) if (!e.isDir && (e.name == SAVE || e.name == PACK)) out[e.name] = e.size
                dir.subdir(STATES, create = false)?.entries()?.forEach { e ->
                    if (!e.isDir && STATE_NAME.matches(e.name)) out["$STATES/${e.name}"] = e.size
                }
                return out
            }
            val fa = files(a)
            if (fa != files(b)) return false
            for (path in fa.keys) {
                val (d, n) = path.indexOf('/').let { if (it < 0) null to path else path.substring(0, it) to path.substring(it + 1) }
                val da = if (d == null) a else a.subdir(d, false) ?: return false
                val db = if (d == null) b else b.subdir(d, false) ?: return false
                val ba = da.openRead(n).use { it.readBytes() }
                val bb = db.openRead(n).use { it.readBytes() }
                if (!ba.contentEquals(bb)) return false
            }
            return true
        }
    }
}
