package com.khumam.gbaduo.emu.shaderlib

import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.emu.ShaderLibrary
import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.SteamGridDb
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Paths inside the libretro repo, always relative to its root. */
internal object ShaderPaths {
    /** [rel] resolved against the folder of [base]; null when it is absolute, empty, or climbs above the root. */
    fun resolve(base: String, rel: String): String? {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains('\\')) return null
        val parts = ArrayList<String>()
        for (part in (base.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + rel).split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }
}

/**
 * Downloads a library preset together with everything it needs, laid out like the repo under [library] so the paths
 * inside presets keep working. Blocking; call off the main thread.
 */
class ShaderDownloader(
    private val http: Http,
    private val library: ShaderLibrary,
    private val tempDir: File,
    private val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {

    /**
     * Downloads [presetPath] and its dependencies at commit [sha] and installs them, or installs nothing: every file is
     * fetched into a temporary folder first and moved into the library only when all of them arrived.
     * [progress] is called after each file with (files done, files known so far).
     *
     * The files are fetched a level at a time ([parallelism] at once: a preset, then everything it names, then what
     * those name...) and handled in order, so the result and the progress are the same as one at a time.
     */
    fun install(presetPath: String, sha: String, progress: (done: Int, total: Int) -> Unit): Fetch<Unit> {
        if (!ShaderLibrary.isSafePath(presetPath) || !presetPath.endsWith(".slangp")) {
            return Fetch.Failed(Fetch.Reason.INSTALL_FAILED, "Not a library preset: $presetPath")
        }
        val staging = File(tempDir, "install-${System.nanoTime()}")
        val pool = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
        try {
            val seen = LinkedHashSet<String>().apply { add(presetPath) }
            var level = listOf(presetPath)
            var done = 0
            while (level.isNotEmpty()) {
                val fetches = level.map { path -> pool.submit(Callable { fetch(sha, path) }) }
                val next = ArrayList<String>()
                for ((i, path) in level.withIndex()) {
                    val bytes = when (val fetched = await { fetches[i].get() }) {
                        is Fetch.Ok -> fetched.value
                        is Fetch.Failed -> return fetched
                    }
                    File(staging, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                    if (isText(path)) {
                        val scan = scan(path, bytes.toString(Charsets.UTF_8))
                        if (scan.rejected.isNotEmpty()) {
                            return Fetch.Failed(Fetch.Reason.INSTALL_FAILED, "$path names a file outside the shader folder: ${scan.rejected.first()}")
                        }
                        for (dep in scan.deps) {
                            if (seen.size >= MAX_FILES) return Fetch.Failed(Fetch.Reason.TOO_BIG, "more than $MAX_FILES files")
                            if (seen.add(dep)) next.add(dep)
                        }
                    }
                    progress(++done, seen.size)
                }
                level = next
            }
            return move(staging, seen)
        } finally {
            pool.shutdownNow()
            staging.deleteRecursively()
        }
    }

    private fun fetch(sha: String, path: String): Fetch<ByteArray> = try {
        val response = http.get(ShaderCatalog.rawUrl(sha, path), emptyMap())
        when {
            response.code != 200 -> Fetch.Failed(Fetch.Reason.DOWNLOAD_FAILED, "$path: HTTP ${response.code}")
            response.body.size > maxFileBytes -> Fetch.Failed(Fetch.Reason.TOO_BIG, path)
            else -> Fetch.Ok(response.body)
        }
    } catch (e: IOException) {
        Fetch.Failed(Fetch.Reason.OFFLINE, e.message)
    }

    /** [get]'s result, with what the fetch threw (not IOException: that is a Failed already) thrown here as it was. */
    private fun <T> await(get: () -> T): T = try {
        get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun move(staging: File, paths: Collection<String>): Fetch<Unit> {
        val created = ArrayList<File>()
        try {
            for (path in paths) {
                val target = library.file(path)
                val isNew = !target.exists()
                target.parentFile?.mkdirs()
                File(staging, path).copyTo(target, overwrite = true)
                if (isNew) created += target
            }
            return Fetch.Ok(Unit)
        } catch (e: IOException) {
            created.forEach { it.delete() }
            return Fetch.Failed(Fetch.Reason.INSTALL_FAILED, e.message)
        }
    }

    companion object {
        /** What [UrlHttp] can read of one file: it stops one byte past this, so a bigger file shows up as over the cap instead of arriving cut short. */
        const val DEFAULT_MAX_FILE_BYTES = SteamGridDb.MAX_DOWNLOAD
        private const val MAX_FILES = 500
        const val DEFAULT_PARALLELISM = 4

        /**
         * Every repo path [presetPath] needs: itself, `#reference`d presets, `shaderN` files, `#include`d files (nested) and
         * the images of `textures`. [read] gives a file's text (null when not available: it is listed but not expanded).
         * Paths that leave the repo root are left out.
         */
        fun dependencies(presetPath: String, read: (String) -> String?): Set<String> {
            val seen = LinkedHashSet<String>().apply { add(presetPath) }
            val queue = ArrayDeque<String>().apply { add(presetPath) }
            while (queue.isNotEmpty() && seen.size < MAX_FILES) {
                val path = queue.removeFirst()
                if (!isText(path)) continue
                val text = read(path) ?: continue
                for (dep in scan(path, text).deps) if (seen.add(dep)) queue.add(dep)
            }
            return seen
        }

        private fun isText(path: String) = path.endsWith(".slangp") || path.endsWith(".slang") || path.endsWith(".inc") || path.endsWith(".h")

        internal class Scan(val deps: List<String>, val rejected: List<String>)

        private val INCLUDE = Regex("""^\s*#include\s+"([^"]+)"""")
        private val REFERENCE = Regex("""^\s*#reference\s+"?([^"\s]+)"?""")

        /** What one file points at, by the rules of a `.slangp` preset or of shader source. */
        internal fun scan(path: String, text: String): Scan {
            val deps = ArrayList<String>()
            val rejected = ArrayList<String>()
            fun add(rel: String) {
                val resolved = ShaderPaths.resolve(path, rel)
                if (resolved == null) rejected += rel else deps += resolved
            }
            if (path.endsWith(".slangp")) {
                val keys = HashMap<String, String>()
                for (line in text.lineSequence()) {
                    REFERENCE.find(line)?.let { add(it.groupValues[1]); return@let }
                    val t = line.trim()
                    if (t.startsWith("#") || !t.contains('=')) continue
                    keys[t.substringBefore('=').trim()] = t.substringAfter('=').trim().trim('"')
                }
                for ((key, value) in keys) if (Regex("shader\\d+").matches(key)) add(value)
                for (id in keys["textures"].orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }) keys[id]?.let(::add)
            } else {
                for (line in text.lineSequence()) INCLUDE.find(line)?.let { add(it.groupValues[1]) }
            }
            return Scan(deps, rejected)
        }
    }
}
